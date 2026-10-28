package dev.jotalac.feature.vault.data

import dev.jotalac.core.utils.suspendRunCatching
import dev.jotalac.feature.git_sync.domain.GitSyncRepository
import dev.jotalac.feature.git_sync.domain.GitSyncStatus
import dev.jotalac.feature.notebooks_management.domain.Notebook
import dev.jotalac.feature.vault.crypto.OpenedVault
import dev.jotalac.feature.vault.crypto.VaultFileCodec
import dev.jotalac.feature.vault.crypto.VaultFormatException
import dev.jotalac.feature.vault.crypto.canonicalRelPath
import dev.jotalac.feature.vault.domain.NoteVault
import dev.jotalac.feature.vault.domain.VaultState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Wraps the platform [GitSyncRepository] with vault awareness for the two operations that touch
 * note content: a sync of an unlocked vault notebook auto-merges the notes whose encrypted files
 * git could not combine (see [VaultMerger]), and conflict names travel to the UI and back as real
 * note paths instead of encrypted names. A locked vault still syncs - git does not need the key -
 * its conflicts keep the raw `.gwv` names.
 */
class VaultGitSyncRepository(
    private val base: GitSyncRepository,

    /**
     * All notebooks by their folder. A late resolver instead of the [NotebookRepository] itself:
     * the notebook repository is built on this interface, so resolving it here would be a cycle.
     */
    private val allNotebooks: suspend () -> List<Notebook>,
    private val vault: NoteVault,
    private val fileStore: VaultFileStore,
    private val merger: VaultMerger,
) : GitSyncRepository {

    private data class VaultSession(val root: String, val opened: OpenedVault)

    /** The unlocked vault session [path] (a notebook folder) belongs to, null for plain or locked notebooks. */
    private suspend fun sessionFor(path: String): VaultSession? {
        val notebooks = allNotebooks()
        val root = VaultLayout.notebookRootOf(notebooks.map { it.directoryPath }, path) ?: return null
        val notebook = notebooks.first { it.directoryPath == root }
        if (vault.stateOf(notebook) !is VaultState.Unlocked) return null
        return vault.sessionForPath(root)?.let { VaultSession(root, it) }
    }

    override suspend fun syncNotes(
        currentNotebookPath: String,
        tokenOrPassword: String,
        username: String?,
        commitMessage: String,
    ): Result<GitSyncStatus> {
        // a merge left over by an earlier sync - a raw conflict or an unresolved dialog - would be
        // committed with its markers by the next one; a vault notebook starts every sync clean,
        // the pull below runs the merge again and an unlocked vault merges it on the content
        if (isVaultNotebook(currentNotebookPath)) base.abortMerge(currentNotebookPath)
        val result = base.syncNotes(currentNotebookPath, tokenOrPassword, username, commitMessage)
        val conflict = result.getOrElse { return result } as? GitSyncStatus.Conflict ?: return result
        val session = sessionFor(currentNotebookPath) ?: return result
        return autoMerge(currentNotebookPath, session, conflict, tokenOrPassword, username, commitMessage)
    }

    /** Whether the notebook at [path] has a vault at all - locked counts, plain notebooks do not. */
    private suspend fun isVaultNotebook(path: String): Boolean {
        val notebooks = allNotebooks()
        val root = VaultLayout.notebookRootOf(notebooks.map { it.directoryPath }, path) ?: return false
        val notebook = notebooks.first { it.directoryPath == root }
        return vault.stateOf(notebook) !is VaultState.Off
    }

    /**
     * Tries to merge every conflicted note; when all of them merge, the merge is committed and
     * pushed. The conflicts left over are reported by the notes' real relative paths - like the
     * plain notebooks report theirs - or raw `.gwv` names when even the path cannot be recovered
     * (the note is not decryptable).
     */
    private suspend fun autoMerge(
        notebookPath: String,
        session: VaultSession,
        conflict: GitSyncStatus.Conflict,
        tokenOrPassword: String,
        username: String?,
        commitMessage: String,
    ): Result<GitSyncStatus> = suspendRunCatching {
        val unmerged = mutableListOf<Pair<String, String?>>() // cname to the real path, null when unknown
        for (file in conflict.files) {
            val cname = file.removeSuffix(VaultFileCodec.FILE_EXTENSION)
            val diskName = cname + VaultFileCodec.FILE_EXTENSION
            val versions = base.getConflictVersions(notebookPath, diskName).getOrNull()
            when (val outcome = versions?.let { merger.mergeOne(cname, it, session.opened) } ?: VaultMerger.Outcome.Manual) {
                is VaultMerger.Outcome.Merged -> {
                    val staged = base.stageFile(notebookPath, diskName, outcome.encryptedContent)
                    if (staged.isFailure) unmerged += cname to null
                }
                is VaultMerger.Outcome.Conflicted -> unmerged += cname to outcome.relPath
                VaultMerger.Outcome.Manual -> unmerged += cname to versions?.let {
                    merger.noteRelPath(cname, it, session.opened)
                }
            }
        }

        if (unmerged.isEmpty()) {
            // every conflict merged - this commit collects the staged notes and completes the merge
            base.commitStaged(notebookPath, commitMessage).getOrThrow()
            // a failed push is not lost - the next sync pushes the merge commit
            base.pushChanges(notebookPath, tokenOrPassword, username)
            GitSyncStatus.UpToDate
        } else {
            GitSyncStatus.Conflict(
                unmerged.map { (cname, relPath) -> relPath ?: cname + VaultFileCodec.FILE_EXTENSION }.toSet(),
            )
        }
    }

    override suspend fun resolveSingleConflict(
        currentNotebookPath: String,
        conflictedFilePath: String,
        keepLocalChanges: Boolean,
    ): Result<Unit> {
        val diskPath = diskNameOf(currentNotebookPath, conflictedFilePath) ?: conflictedFilePath
        return base.resolveSingleConflict(currentNotebookPath, diskPath, keepLocalChanges)
    }

    /** The `.gwv` name of a note path; null when it cannot be translated (locked vault, unknown path). */
    private suspend fun diskNameOf(notebookPath: String, filePath: String): String? {
        // a raw `.gwv` path arrives already translated - a locked vault reports those
        if (filePath.endsWith(VaultFileCodec.FILE_EXTENSION)) return filePath
        val session = sessionFor(notebookPath) ?: return null
        val relPath = try {
            if (VaultLayout.isUnder(session.root, filePath)) {
                fileStore.requireRelPath(session.root, session.opened.vault, filePath)
            } else {
                // repo-relative, the way conflict names travel between the UI and here
                canonicalRelPath(filePath)
            }
        } catch (_: IllegalArgumentException) {
            return null
        } catch (_: VaultFormatException) {
            return null
        }
        return session.opened.vault.fileNameOf(relPath)
    }

    override suspend fun validateCredentials(repoUrl: String, tokenOrPassword: String, username: String?): Result<Unit> =
        base.validateCredentials(repoUrl, tokenOrPassword, username)

    override suspend fun cloneRepository(
        repoUrl: String,
        tokenOrPassword: String,
        destinationPath: String,
        username: String?,
    ): Result<Unit> = base.cloneRepository(repoUrl, tokenOrPassword, destinationPath, username)

    override suspend fun initRepository(currentNotebookPath: String): Result<Unit> = base.initRepository(currentNotebookPath)

    override suspend fun resolveAllConflicts(currentNotebookPath: String, keepLocalChanges: Boolean): Result<Unit> =
        base.resolveAllConflicts(currentNotebookPath, keepLocalChanges)

    override suspend fun pushChanges(currentNotebookPath: String, tokenOrPassword: String, username: String?): Result<Unit> =
        base.pushChanges(currentNotebookPath, tokenOrPassword, username)

    override suspend fun updateRemoteUrl(currentNotebookPath: String, newRemoteUrl: String): Result<Unit> =
        base.updateRemoteUrl(currentNotebookPath, newRemoteUrl)

    override suspend fun abortMerge(currentNotebookPath: String): Result<Unit> = base.abortMerge(currentNotebookPath)

    override suspend fun removeRemote(currentNotebookPath: String): Result<Unit> = base.removeRemote(currentNotebookPath)

    override fun updateSyncStatus(remoteUrl: String?, syncStatus: GitSyncStatus?): Result<Unit> =
        base.updateSyncStatus(remoteUrl, syncStatus)

    override suspend fun resetHistoryToCurrentState(currentNotebookPath: String, commitMessage: String): Result<Unit> =
        base.resetHistoryToCurrentState(currentNotebookPath, commitMessage)

    override suspend fun forcePush(currentNotebookPath: String, tokenOrPassword: String, username: String?): Result<Unit> =
        base.forcePush(currentNotebookPath, tokenOrPassword, username)

    override suspend fun resetToRemote(currentNotebookPath: String, tokenOrPassword: String, username: String?): Result<Unit> =
        base.resetToRemote(currentNotebookPath, tokenOrPassword, username)

    override suspend fun getConflictVersions(
        currentNotebookPath: String,
        conflictedFilePath: String,
    ): Result<Map<Int, ByteArray>> = base.getConflictVersions(currentNotebookPath, conflictedFilePath)

    override suspend fun stageFile(currentNotebookPath: String, filePath: String, content: ByteArray): Result<Unit> =
        base.stageFile(currentNotebookPath, filePath, content)

    override suspend fun setRepoIdentity(currentNotebookPath: String): Result<Unit> =
        base.setRepoIdentity(currentNotebookPath)

    override suspend fun commitStaged(currentNotebookPath: String, commitMessage: String): Result<Unit> =
        base.commitStaged(currentNotebookPath, commitMessage)

    override val gitSyncStatus: Flow<GitSyncStatus> = base.gitSyncStatus
}
