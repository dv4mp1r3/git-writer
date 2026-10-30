package dev.jotalac.feature.vault.data

import dev.jotalac.core.utils.deleteRecursively
import dev.jotalac.feature.git_sync.domain.GitSyncStatus
import dev.jotalac.feature.notebooks_management.domain.Notebook
import dev.jotalac.feature.vault.domain.VaultState
import dev.jotalac.testing.fakes.FakeGitSyncRepository
import dev.jotalac.testing.fakes.FakeNotebookRepository
import dev.jotalac.testing.fakes.InMemorySecureKeyCache
import kotlinx.coroutines.test.runTest
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/*
* The enable / disable / adopt flows against a fake git repository. The note conversion is checked
* on the real files, the git part by what the controller recorded on the fake.
* */
class VaultControllerTest {

    private val root = Path(SystemTemporaryDirectory, "vault-controller-test-${Random.nextInt()}")
    private val notebooks = FakeNotebookRepository()
    private val secureStorage = InMemorySecureKeyCache()
    private val fileStore = VaultFileStore()
    private val vault = VaultManagerImpl(
        allNotebooks = { notebooks.getAllNotebooks() },
        secureKeyStorage = secureStorage,
        fileStore = fileStore,
        autoRefresh = false,
    )
    private val git = FakeGitSyncRepository()
    private val controller = VaultController(git, vault, fileStore)

    @BeforeTest
    fun setUp() {
        SystemFileSystem.createDirectories(root)
        git.syncResult = Result.success(GitSyncStatus.UpToDate)
    }

    @AfterTest
    fun cleanUp() = root.deleteRecursively()

    // --- helpers

    /** A notebook folder with one plain note; [withGit] adds the fake `.git` marker of a git notebook. */
    private fun device(id: Long, name: String, withGit: Boolean): Notebook {
        val path = Path(root, name).toString()
        SystemFileSystem.createDirectories(Path(path))
        if (withGit) SystemFileSystem.createDirectories(Path(path, ".git"))
        writeNote(path, "a.md", "plain content")
        val notebook = Notebook(
            id = id,
            name = name,
            directoryPath = path,
            remoteUrl = if (withGit) "https://example.com/repo.git" else null,
            remoteUsername = if (withGit) "user" else null,
            remotePassword = if (withGit) "token" else null,
        )
        notebooks.notebooks.value += notebook
        return notebook
    }

    private fun writeNote(directory: String, name: String, content: String) {
        SystemFileSystem.sink(Path(directory, name)).buffered().use { it.writeString(content) }
    }

    private fun readNote(directory: String, name: String): String =
        SystemFileSystem.source(Path(directory, name)).buffered().use { it.readString() }

    private fun encryptedNames(directory: String): List<String> =
        SystemFileSystem.list(Path(directory))
            .filter { it.name.endsWith(VaultLayout.ENCRYPTED_EXTENSION) }
            .map { it.name }

    // --- tests

    @Test
    fun `enable encrypts the notes and rewrites the history for a remote notebook`() = runTest {
        val notebook = device(1, "notes", withGit = true)
        val dir = notebook.directoryPath

        controller.enableEncryption(notebook, "pass").getOrThrow()

        // the note is gone, an encrypted file and the keystore are in its place
        assertFalse(SystemFileSystem.exists(Path(dir, "a.md")))
        assertEquals(1, encryptedNames(dir).size)
        assertTrue(SystemFileSystem.exists(VaultLayout.keystorePath(Path(dir))))
        assertIs<VaultState.Unlocked>(vault.stateOf(notebook))

        // the git side: anonymous identity, history replaced, force pushed
        assertEquals(listOf(dir), git.identitiesSet)
        assertEquals(listOf(dir to "Encrypt notes"), git.resetHistories)
        assertEquals(listOf(dir), git.forcedPushes)
    }

    @Test
    fun `enable without a remote only converts the notes`() = runTest {
        val notebook = device(2, "local", withGit = false)
        val dir = notebook.directoryPath

        controller.enableEncryption(notebook, "pass").getOrThrow()

        assertFalse(SystemFileSystem.exists(Path(dir, "a.md")))
        assertEquals(1, encryptedNames(dir).size)
        assertTrue(SystemFileSystem.exists(VaultLayout.keystorePath(Path(dir))))
        assertIs<VaultState.Unlocked>(vault.stateOf(notebook))

        assertEquals(emptyList(), git.identitiesSet)
        assertEquals(emptyList(), git.resetHistories)
        assertEquals(emptyList(), git.forcedPushes)
    }

    @Test
    fun `disable restores the plaintext and removes the vault`() = runTest {
        val notebook = device(3, "notes", withGit = true)
        val dir = notebook.directoryPath
        controller.enableEncryption(notebook, "pass").getOrThrow()

        controller.disableEncryption(notebook).getOrThrow()

        assertEquals("plain content", readNote(dir, "a.md"))
        assertEquals(emptyList(), encryptedNames(dir))
        assertFalse(SystemFileSystem.exists(VaultLayout.keystorePath(Path(dir))))
        assertIs<VaultState.Off>(vault.stateOf(notebook))

        assertEquals(
            listOf(dir to "Encrypt notes", dir to "Decrypt notes"),
            git.resetHistories,
        )
        assertEquals(listOf(dir, dir), git.forcedPushes)
    }

    @Test
    fun `adopt catches up with the remote and keeps the local notes under new names`() = runTest {
        val notebook = device(4, "phone", withGit = true)
        val dir = notebook.directoryPath
        controller.enableEncryption(notebook, "pass").getOrThrow()

        // the remote already delivers a note with that name - it is in the encrypted tree
        val session = vault.sessionForPath(dir)!!.vault
        fileStore.write(dir, session, "local.md", "from the remote".encodeToByteArray())
        vault.lock(notebook)

        // typed while the notebook was locked: a plain note next to the encrypted tree
        writeNote(dir, "local.md", "typed offline")

        controller.adoptEncryptedNotebook(notebook, "pass").getOrThrow()

        assertIs<VaultState.Unlocked>(vault.stateOf(notebook))
        assertFalse(SystemFileSystem.exists(Path(dir, "local.md")))
        assertEquals(listOf(dir), git.resetToRemotes)
        // the enable-time stale-clone sync plus the adopt's own sync of the restored notes
        assertEquals(listOf(dir, dir), git.syncedPaths)

        // the local note came back encrypted under the next free name
        val reopened = vault.sessionForPath(dir)!!.vault
        val names = fileStore.listNoteRelPaths(dir, reopened)
        assertEquals(3, names.size)
        assertTrue("a.md" in names, "expected a.md in $names")
        assertTrue("local.md" in names, "expected the remote note in $names")
        assertTrue("local 1.md" in names, "expected the deduplicated local note in $names")
        assertEquals(
            "typed offline",
            fileStore.read(dir, reopened, "local 1.md")!!.decodeToString(),
        )
    }

    @Test
    fun `enable writes the gitignore entry that keeps the keystore out of the repository`() = runTest {
        val notebook = device(5, "notes", withGit = true)
        val dir = notebook.directoryPath

        controller.enableEncryption(notebook, "pass").getOrThrow()

        val gitignore = readNote(dir, ".gitignore")
        assertTrue(".gw-vault/" in gitignore, "expected the vault entry in: $gitignore")
        assertTrue(VaultLayout.keystoreExists(dir), "the local keystore copy stays")
    }

    @Test
    fun `purging the history replaces it and force pushes the fresh commit`() = runTest {
        val notebook = device(6, "notes", withGit = true)
        val dir = notebook.directoryPath
        controller.enableEncryption(notebook, "pass").getOrThrow()

        controller.purgeVaultHistory(notebook).getOrThrow()

        assertEquals(
            listOf(dir to "Encrypt notes", dir to "Remove the vault keystore from history"),
            git.resetHistories,
        )
        assertEquals(listOf(dir, dir), git.forcedPushes)
        assertTrue(SystemFileSystem.exists(VaultLayout.keystorePath(Path(dir))))
    }

    @Test
    fun `purging a notebook without git does nothing`() = runTest {
        val notebook = device(7, "local", withGit = false)

        controller.purgeVaultHistory(notebook).getOrThrow()

        assertEquals(emptyList(), git.resetHistories)
        assertEquals(emptyList(), git.forcedPushes)
    }

    @Test
    fun `enabling on a stale clone of an encrypted notebook syncs before minting a key`() = runTest {
        // a fresh clone reads as Off until the first pull brings the `.gwv` files in
        val notebook = device(8, "clone", withGit = true)

        controller.enableEncryption(notebook, "pass").getOrThrow()

        assertEquals(listOf(notebook.directoryPath), git.syncedPaths)
    }
}
