package dev.jotalac.feature.vault.data

import dev.jotalac.core.utils.deleteRecursively
import dev.jotalac.feature.git_sync.domain.GitSyncStatus
import dev.jotalac.feature.notebooks_management.domain.Notebook
import dev.jotalac.testing.fakes.FakeGitSyncRepository
import dev.jotalac.testing.fakes.FakeNotebookRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.seconds

/*
* The one-time migration that takes the keystore out of the repositories, against a fake git. The
* file effects (`.gitignore`) are checked on the real folders, the git part on what the fake recorded.
* */
class VaultKeystoreMigrationTest {

    private val root = Path(SystemTemporaryDirectory, "vault-migration-test-${Random.nextInt()}")
    private val notebooks = FakeNotebookRepository()
    private val git = FakeGitSyncRepository()
    private val migration = VaultKeystoreMigration(
        notebookRepository = notebooks,
        git = git,
        fileStore = VaultFileStore(),
    )

    @BeforeTest
    fun setUp() {
        SystemFileSystem.createDirectories(root)
    }

    @AfterTest
    fun cleanUp() = root.deleteRecursively()

    // --- helpers

    /** A notebook folder with the fake `.git` marker of a git notebook and a remote behind it. */
    private fun device(id: Long, name: String): Notebook {
        val path = Path(root, name).toString()
        SystemFileSystem.createDirectories(Path(path))
        SystemFileSystem.createDirectories(Path(path, ".git"))
        val notebook = Notebook(id, name, path, "https://example.com/repo.git", "user", "token")
        notebooks.notebooks.value += notebook
        return notebook
    }

    private fun putKeystore(dir: String) {
        SystemFileSystem.createDirectories(Path(dir, VaultLayout.KEYSTORE_DIR))
        SystemFileSystem.sink(VaultLayout.keystorePath(Path(dir))).buffered().use {
            it.writeString("{\"version\":1,\"key_id\":\"abcd\"}")
        }
    }

    private fun readText(path: Path): String =
        SystemFileSystem.source(path).buffered().use { it.readString() }

    // --- tests

    @Test
    fun `a tracked keystore is untracked, ignored, committed and pushed`() = runTest {
        val notebook = device(1, "notes")
        putKeystore(notebook.directoryPath)
        git.trackedPaths = setOf(VaultLayout.KEYSTORE_GIT_PATH)

        migration.runAll()

        assertEquals(
            listOf(notebook.directoryPath to VaultLayout.KEYSTORE_GIT_PATH),
            git.untrackedFiles,
        )
        assertEquals(
            listOf(notebook.directoryPath to "Stop tracking the vault keystore"),
            git.committedStaged,
        )
        assertEquals(listOf(notebook.directoryPath), git.identitiesSet)
        assertEquals(listOf(notebook.directoryPath), git.pushedPaths)
        // the ignore entry is committed together with the removal
        val stagedIgnore = git.stagedFiles.single { it.second == ".gitignore" }
        assertEquals(notebook.directoryPath, stagedIgnore.first)
        assertContentEquals(".gw-vault/\n".encodeToByteArray(), stagedIgnore.third)
        assertTrue(SystemFileSystem.exists(VaultLayout.keystorePath(Path(notebook.directoryPath))), "the local file stays")
        assertEquals(setOf(1L), migration.pendingHistoryCleanup.value)
    }

    @Test
    fun `an untracked keystore only gets the ignore entry and no cleanup offer`() = runTest {
        // the notebook was always local, or the migration ran before
        val notebook = device(1, "notes")
        putKeystore(notebook.directoryPath)

        migration.runAll()

        assertEquals(emptyList(), git.committedStaged)
        assertEquals(emptyList(), git.pushedPaths)
        assertTrue(migration.pendingHistoryCleanup.value.isEmpty())
        assertEquals(".gw-vault/\n", readText(Path(notebook.directoryPath, ".gitignore")))
    }

    @Test
    fun `a plain notebook is left alone`() = runTest {
        val notebook = device(1, "plain")

        migration.runAll()

        assertEquals(emptyList(), git.untrackedFiles)
        assertFalse(SystemFileSystem.exists(Path(notebook.directoryPath, ".gitignore")))
    }

    @Test
    fun `a notebook without a repository only gets the ignore entry`() = runTest {
        val notebook = device(1, "local").let {
            SystemFileSystem.delete(Path(it.directoryPath, ".git"))
            it
        }
        putKeystore(notebook.directoryPath)

        migration.runAll()

        assertEquals(emptyList(), git.untrackedFiles)
        assertEquals(".gw-vault/\n", readText(Path(notebook.directoryPath, ".gitignore")))
    }

    @Test
    fun `the user's own gitignore entries survive`() = runTest {
        val notebook = device(1, "notes")
        putKeystore(notebook.directoryPath)
        SystemFileSystem.sink(Path(notebook.directoryPath, ".gitignore")).buffered().use {
            it.writeString("build/\n")
        }

        migration.runAll()

        assertEquals("build/\n.gw-vault/\n", readText(Path(notebook.directoryPath, ".gitignore")))
    }

    @Test
    fun `the migration runs once per session and the pending flag can be cleared`() = runTest {
        val notebook = device(1, "notes")
        putKeystore(notebook.directoryPath)
        git.trackedPaths = setOf(VaultLayout.KEYSTORE_GIT_PATH)

        migration.runAll()
        migration.runAll() // second pass - already done

        assertEquals(1, git.untrackedFiles.size, "a processed notebook is not touched again")
        assertEquals(setOf(1L), migration.pendingHistoryCleanup.value)
        migration.clearPendingHistoryCleanup(1)
        assertTrue(migration.pendingHistoryCleanup.value.isEmpty())
    }

    @Test
    fun `a sync re-runs the migration for older app versions that re-track the keystore`() = runTest {
        val notebook = device(1, "notes")
        putKeystore(notebook.directoryPath)
        git.trackedPaths = setOf(VaultLayout.KEYSTORE_GIT_PATH)
        migration.start()

        // the initial pass has untracked the keystore
        waitUntil("the initial migration pass") { git.untrackedFiles.isNotEmpty() }
        assertEquals(listOf(notebook.directoryPath to VaultLayout.KEYSTORE_GIT_PATH), git.untrackedFiles)

        // an older app version committed the keystore again - the next successful sync repeats it
        git.trackedPaths = setOf(VaultLayout.KEYSTORE_GIT_PATH)
        git.status.tryEmit(GitSyncStatus.UpToDate)
        waitUntil("the re-run after the sync") { git.untrackedFiles.size >= 2 }
    }

    /** Polls [condition] on the real clock; the migration works on background dispatchers. */
    private suspend fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = TimeSource.Monotonic.markNow() + 10.seconds
        while (!condition()) {
            assertTrue(deadline.hasNotPassedNow(), "$what did not happen in time")
            withContext(Dispatchers.IO) { delay(10) }
        }
    }
}
