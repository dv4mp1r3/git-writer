package dev.jotalac.feature.vault.data

import dev.jotalac.core.utils.deleteRecursively
import dev.jotalac.feature.editor.data.EditorRepositoryImpl
import dev.jotalac.feature.git_sync.domain.GitSyncRepository
import dev.jotalac.feature.git_sync.domain.GitSyncStatus
import dev.jotalac.feature.notebooks_management.domain.Notebook
import dev.jotalac.feature.vault.domain.VaultState
import dev.jotalac.testing.fakes.FakeNotebookRepository
import dev.jotalac.testing.fakes.InMemorySecureKeyCache
import kotlinx.coroutines.flow.first
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

/*
* The vault-aware sync against real git repositories - JGit and libgit2 both run this. Two devices
* share one encrypted notebook through a bare remote; edits go through the vault editor decorator
* and syncs through the vault git decorator, exactly like in the app.
* */
abstract class VaultSyncContractTest {

    /** The platform git repository - JGit on desktop and Android, libgit2 on iOS. */
    protected abstract val git: GitSyncRepository

    /** A bare repository at [path] without any commit - a server repository that was just created. */
    protected abstract fun createEmptyRemote(path: String)

    private val root = Path(SystemTemporaryDirectory, "vault-sync-test-${Random.nextLong().toULong()}")
    private val remote = Path(root, "remote.git").toString()

    private val notebooks = FakeNotebookRepository()
    private val secureStorage = InMemorySecureKeyCache()
    private val fileStore = VaultFileStore()
    private val vault = VaultManagerImpl(
        allNotebooks = { notebooks.getAllNotebooks() },
        secureKeyStorage = secureStorage,
        fileStore = fileStore,
        autoRefresh = false,
    )
    private val editor = VaultEditorRepository(EditorRepositoryImpl(), notebooks, vault, fileStore)

    // lazy: [git] is only assigned after the superclass constructor has run
    private val repository: VaultGitSyncRepository by lazy {
        VaultGitSyncRepository(
            base = git,
            allNotebooks = { notebooks.getAllNotebooks().first() },
            vault = vault,
            fileStore = fileStore,
            merger = VaultMerger(),
        )
    }

    @BeforeTest
    fun setUp() {
        SystemFileSystem.createDirectories(root)
        createEmptyRemote(remote)
    }

    @AfterTest
    fun cleanUp() = root.deleteRecursively()

    @Test
    fun `edits of different parts of one note merge on sync`() = runTest {
        val laptop = vaultDevice(1, "laptop")
        val laptopNote = editor.createNote(laptop.directoryPath, "note", "first\nsecond\nthird").getOrThrow()
        sync(laptop.directoryPath).getOrThrow()

        val phone = vaultCloneDevice(2, "phone")
        sync(phone.directoryPath).getOrThrow()

        editor.saveFile("laptop\nsecond\nthird", laptopNote).getOrThrow()
        val phoneNote = Path(phone.directoryPath, "note.md").toString()
        editor.saveFile("first\nsecond\nphone", phoneNote).getOrThrow()
        sync(phone.directoryPath).getOrThrow()

        val result = sync(laptop.directoryPath)

        assertEquals(GitSyncStatus.UpToDate, result.getOrThrow())
        assertEquals("laptop\nsecond\nphone", editor.readNoteContent(laptopNote).getOrThrow())
        // the phone picks the merge up on its next sync
        sync(phone.directoryPath).getOrThrow()
        assertEquals("laptop\nsecond\nphone", editor.readNoteContent(phoneNote).getOrThrow())
    }

    @Test
    fun `a real conflict surfaces as the note path and resolves like in a plain notebook`() = runTest {
        val laptop = vaultDevice(1, "laptop")
        val laptopNote = editor.createNote(laptop.directoryPath, "note", "base").getOrThrow()
        sync(laptop.directoryPath).getOrThrow()

        val phone = vaultCloneDevice(2, "phone")
        val phoneNote = Path(phone.directoryPath, "note.md").toString()
        editor.saveFile("phone", phoneNote).getOrThrow()
        sync(phone.directoryPath).getOrThrow()

        editor.saveFile("laptop", laptopNote).getOrThrow()

        assertEquals(GitSyncStatus.Conflict(setOf("note.md")), sync(laptop.directoryPath).getOrThrow())

        repository.resolveSingleConflict(laptop.directoryPath, "note.md", keepLocalChanges = true).getOrThrow()
        assertEquals(GitSyncStatus.UpToDate, sync(laptop.directoryPath).getOrThrow())
        assertEquals("laptop", editor.readNoteContent(laptopNote).getOrThrow())
        sync(phone.directoryPath).getOrThrow()
        assertEquals("laptop", editor.readNoteContent(phoneNote).getOrThrow())
    }

    @Test
    fun `a locked vault reports raw names and finishes the sync after unlocking`() = runTest {
        val laptop = vaultDevice(1, "laptop")
        val laptopNote = editor.createNote(laptop.directoryPath, "secret", "base").getOrThrow()
        sync(laptop.directoryPath).getOrThrow()

        val phone = vaultCloneDevice(2, "phone")
        val phoneNote = Path(phone.directoryPath, "secret.md").toString()
        editor.saveFile("phone", phoneNote).getOrThrow()
        sync(phone.directoryPath).getOrThrow()

        editor.saveFile("laptop", laptopNote).getOrThrow()
        vault.lock(laptop)

        val locked = assertIs<GitSyncStatus.Conflict>(sync(laptop.directoryPath).getOrThrow())
        val rawName = locked.files.single()
        assertTrue(rawName.endsWith(".gwv"), "a locked vault cannot translate names, got $rawName")

        vault.unlock(laptop, "pass").getOrThrow()

        // the same line changed on both sides - a real conflict, now under the real name
        assertEquals(GitSyncStatus.Conflict(setOf("secret.md")), sync(laptop.directoryPath).getOrThrow())

        repository.resolveSingleConflict(laptop.directoryPath, "secret.md", keepLocalChanges = true).getOrThrow()
        assertEquals(GitSyncStatus.UpToDate, sync(laptop.directoryPath).getOrThrow())
        assertEquals("laptop", editor.readNoteContent(laptopNote).getOrThrow())
        sync(phone.directoryPath).getOrThrow()
        assertEquals("laptop", editor.readNoteContent(phoneNote).getOrThrow())
    }

    @Test
    fun `a plain notebook passes through the decorator untouched`() = runTest {
        val plain = Path(root, "plain").toString()
        SystemFileSystem.createDirectories(Path(plain))
        repository.initRepository(plain).getOrThrow()
        repository.updateRemoteUrl(plain, remote).getOrThrow()
        // a sync of a repository without a single commit cannot push - the note makes the first commit
        SystemFileSystem.sink(Path(plain, "a.md")).buffered().use { it.writeString("plain") }

        assertEquals(GitSyncStatus.UpToDate, sync(plain).getOrThrow())

        val check = Path(root, "check").toString()
        repository.cloneRepository(remote, "token", check, "user").getOrThrow()
        assertEquals("plain", read(check, "a.md"))
    }

    // --- helpers

    /** A fresh notebook with a vault, connected to the empty remote - the first device. */
    private suspend fun vaultDevice(id: Long, name: String): Notebook {
        val path = Path(root, name).toString()
        SystemFileSystem.createDirectories(Path(path))
        repository.initRepository(path).getOrThrow()
        repository.updateRemoteUrl(path, remote).getOrThrow()
        val notebook = Notebook(id, name, path, remote, "user", "token")
        notebooks.notebooks.value += notebook
        vault.createVault(notebook, "pass").getOrThrow()
        return notebook
    }

    /** A clone of what the first device pushed. The shared key cache stands in for a given key, so it comes up unlocked. */
    private suspend fun vaultCloneDevice(id: Long, name: String): Notebook {
        val path = Path(root, name).toString()
        repository.cloneRepository(remote, "token", path, "user").getOrThrow()
        val notebook = Notebook(id, name, path, remote, "user", "token")
        notebooks.notebooks.value += notebook
        vault.refreshStates()
        assertIs<VaultState.Unlocked>(vault.stateOf(notebook))
        return notebook
    }

    private suspend fun sync(notebookPath: String) = repository.syncNotes(notebookPath, "token", "user", "sync")

    private fun read(device: String, note: String): String =
        SystemFileSystem.source(Path(device, note)).buffered().use { it.readString() }
}
