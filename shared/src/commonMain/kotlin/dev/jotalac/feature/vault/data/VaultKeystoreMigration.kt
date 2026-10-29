package dev.jotalac.feature.vault.data

import dev.jotalac.feature.git_sync.domain.GitSyncRepository
import dev.jotalac.feature.git_sync.domain.GitSyncStatus
import dev.jotalac.feature.notebooks_management.domain.Notebook
import dev.jotalac.feature.notebooks_management.domain.NotebookRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/**
 * One-time per-notebook migration that takes the keystore out of the notes repository: writes the
 * `.gitignore` entry, untracks `.gw-vault/keystore` (the file stays on the device), then commits
 * and pushes the removal. Notebooks whose repository history still holds the keystore surface in
 * [pendingHistoryCleanup] - only a history rewrite ([VaultController.purgeVaultHistory]) can clean
 * those, and the UI asks the user before doing it.
 *
 * Best effort by design: a failed step is retried on the next trigger and never disturbs normal
 * usage. Runs again after every successful sync, because `.gitignore` does not protect a path
 * that an app version from before this migration keeps committing.
 */
class VaultKeystoreMigration(
    /** Nothing depends on the migration, so the notebook repository can be a plain dependency
     *  (unlike [VaultManagerImpl], which sits inside the repository's own dependency chain). */
    private val notebookRepository: NotebookRepository,
    private val git: GitSyncRepository,
    private val fileStore: VaultFileStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val pending = MutableStateFlow<Set<Long>>(emptySet())

    /** Notebooks whose repository history still holds a keystore - the cleanup dialog awaits them. */
    val pendingHistoryCleanup: StateFlow<Set<Long>> = pending.asStateFlow()

    private val mutex = Mutex()

    /** Notebooks processed in this app session; reset after every sync (see the class doc). */
    private val doneNotebookIds = mutableSetOf<Long>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var started = false

    /** Watches the notebook list and the sync status; harmless to call more than once. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            notebookRepository.getAllNotebooks().collect {
                runCatching { runAll() }
            }
        }
        scope.launch {
            git.gitSyncStatus.collect { status ->
                if (status is GitSyncStatus.UpToDate) {
                    mutex.withLock { doneNotebookIds.clear() }
                    runCatching { runAll() }
                }
            }
        }
    }

    suspend fun runAll() {
        for (notebook in notebookRepository.getAllNotebooks().first()) {
            migrateIfNeeded(notebook)
        }
    }

    /** Stops offering the history-cleanup dialog for [notebookId] (confirmed, or dismissed for now). */
    fun clearPendingHistoryCleanup(notebookId: Long) {
        pending.update { it - notebookId }
    }

    suspend fun migrateIfNeeded(notebook: Notebook) {
        mutex.withLock {
            if (notebook.id in doneNotebookIds) return
            // a failure (repo lock, merge in progress, ...) leaves the notebook out of the done
            // set, so the next trigger retries; the exception itself is migration-internal
            val migrated = withContext(ioDispatcher) { runCatching { migrate(notebook) } }
            if (migrated.isSuccess) doneNotebookIds += notebook.id
        }
    }

    /** @throws Exception when a git step failed - the caller retries on the next trigger. */
    private suspend fun migrate(notebook: Notebook) {
        val dir = notebook.directoryPath
        // the ignore entry only makes sense for vault notebooks - plain ones keep a clean .gitignore
        val isVault = VaultLayout.keystoreExists(dir) || fileStore.firstKeyIdHexBlocking(dir) != null
        if (!isVault) return
        ensureKeystoreIgnored(dir)
        if (SystemFileSystem.metadataOrNull(Path(dir, ".git")) == null) return // not a repo - nothing to untrack

        // false = the keystore is not tracked (already migrated, or the notebook was always local)
        if (!git.untrackFile(dir, VaultLayout.KEYSTORE_GIT_PATH).getOrThrow()) return

        git.setRepoIdentity(dir).getOrThrow()
        val gitignore = Path(dir, ".gitignore")
        val ignoreBytes = if (SystemFileSystem.exists(gitignore)) {
            SystemFileSystem.source(gitignore).buffered().use { it.readByteArray() }
        } else {
            ByteArray(0)
        }
        git.stageFile(dir, GITIGNORE_PATH, ignoreBytes).getOrThrow()
        git.commitStaged(dir, UNTRACK_MESSAGE).getOrThrow()

        val password = notebook.remotePassword
        if (!notebook.remoteUrl.isNullOrBlank() && password != null) {
            // best effort - a rejected push catches up with the next regular sync
            runCatching { git.pushChanges(dir, password, notebook.remoteUsername) }
        }
        pending.update { it + notebook.id }
    }

    private companion object {
        const val GITIGNORE_PATH = ".gitignore"
        const val UNTRACK_MESSAGE = "Stop tracking the vault keystore"
    }
}
