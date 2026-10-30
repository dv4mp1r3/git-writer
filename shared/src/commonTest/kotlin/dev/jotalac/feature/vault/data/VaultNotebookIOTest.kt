package dev.jotalac.feature.vault.data

import dev.jotalac.core.utils.deleteRecursively
import dev.jotalac.feature.editor.data.EditorRepositoryImpl
import dev.jotalac.feature.editor_sidebar.domain.FileNode
import dev.jotalac.feature.notebooks_management.domain.Notebook
import dev.jotalac.feature.vault.crypto.Base32
import dev.jotalac.feature.vault.crypto.RecoveryKeyCodec
import dev.jotalac.feature.vault.crypto.VaultFormatException
import dev.jotalac.feature.vault.crypto.VaultKeystoreConflictException
import dev.jotalac.feature.vault.domain.VaultLockedException
import dev.jotalac.feature.vault.domain.VaultState
import dev.jotalac.testing.fakes.FakeNotebookRepository
import dev.jotalac.testing.fakes.InMemorySecureKeyCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
* End-to-end vault I/O on real temporary folders, no git: the manager + the decorator over the
* plain repository implementation. Covers encrypt-on-write, decrypt-on-read, listing, renames,
* moves, deletes, locked vaults and orphaned encrypted files.
* */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultNotebookIOTest {

    private lateinit var notebookDir: Path
    private lateinit var notebook: Notebook
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

    @BeforeTest
    fun createNotebookFolder() {
        notebookDir = Path(SystemTemporaryDirectory, "git_writer_vault_test_${Random.nextInt()}")
        SystemFileSystem.createDirectories(notebookDir)
        notebook = Notebook(1, "Test", notebookDir.toString(), null, null, null)
        notebooks.notebooks.value = listOf(notebook)
        notebooks.activeNotebook.value = notebook
    }

    @AfterTest
    fun deleteNotebookFolder() {
        notebookDir.deleteRecursively()
    }

    private fun rootPath(relative: String): String = Path(notebookDir, relative).toString()

    private fun diskFiles(): List<String> =
        SystemFileSystem.list(notebookDir).map { it.name }.sorted()

    private fun writePlain(relative: String, content: String) {
        val path = Path(notebookDir, relative)
        path.parent?.let { SystemFileSystem.createDirectories(it) }
        SystemFileSystem.sink(path).buffered().use { it.writeString(content) }
    }

    // --- enabling and listing

    @Test
    fun `created notes are stored as one encrypted file and listed back`() = runTest {
        vault.createVault(notebook, "pass").getOrThrow()

        val notePath = editor.createNote(notebookDir.toString(), "untitled").getOrThrow()
        assertTrue(notePath.startsWith(notebookDir.toString()), "the decorator returns a virtual path")

        // on disk there is exactly one encrypted file, the keystore and the ignore entry - no plaintext
        val files = diskFiles()
        assertEquals(3, files.size, "expected one .gwv + keystore dir + .gitignore, got $files")
        assertTrue(files.any { it.endsWith(".gwv") })
        assertTrue(files.contains(VaultLayout.KEYSTORE_DIR))
        assertFalse(files.any { it.endsWith(".md") })

        vault.refreshStates()
        assertIs<VaultState.Unlocked>(vault.stateOf(notebook))

        val tree = vault.buildFileTree(notebook)
        val noteNode = assertIs<FileNode.File>(tree?.children?.single())
        assertEquals("untitled.md", noteNode.name)
        assertEquals(notePath, noteNode.path)
        assertTrue(noteNode.diskPath!!.endsWith(".gwv"))
        assertTrue(editor.fileExists(notePath))
    }

    @Test
    fun `note content roundtrips through encryption`() = runTest {
        vault.createVault(notebook, "pass").getOrThrow()
        val notePath = editor.createNote(notebookDir.toString(), "Заметка", "# Заголовок\n\nтекст").getOrThrow()

        assertEquals("# Заголовок\n\nтекст", editor.readNoteContent(notePath).getOrThrow())

        editor.saveFile("новое содержимое", notePath).getOrThrow()
        assertEquals("новое содержимое", editor.readNoteContent(notePath).getOrThrow())

        val blocks = editor.loadMarkdownFileBlocks(notePath).getOrThrow()
        assertTrue(blocks.isNotEmpty())
        assertTrue(blocks.joinToString("") { it }.contains("новое содержимое"))
    }

    @Test
    fun `duplicate note names get a counter like in plain notebooks`() = runTest {
        vault.createVault(notebook, "pass").getOrThrow()
        val first = editor.createNote(notebookDir.toString(), "untitled").getOrThrow()
        val second = editor.createNote(notebookDir.toString(), "untitled").getOrThrow()
        assertTrue(first != second)
        assertTrue(second.endsWith("untitled 1.md"))
        assertTrue(editor.fileExists(first) && editor.fileExists(second))
    }

    // --- renames and moves

    @Test
    fun `renaming a note re-encrypts it under the new name`() = runTest {
        vault.createVault(notebook, "pass").getOrThrow()
        val notePath = editor.createNote(notebookDir.toString(), "before", "content").getOrThrow()
        val oldDiskName = diskFiles().single { it.endsWith(".gwv") }

        editor.renameItem(notePath, "after.md").getOrThrow()

        val newDiskName = diskFiles().single { it.endsWith(".gwv") }
        assertEquals(1, diskFiles().count { it.endsWith(".gwv") })
        assertTrue(oldDiskName != newDiskName, "the encrypted file name follows the note path")
        val newPath = Path(notebookDir, "after.md").toString()
        assertEquals("content", editor.readNoteContent(newPath).getOrThrow())
        assertFalse(editor.fileExists(notePath))
    }

    // --- folders

    @Test
    fun `folder rename moves every note inside`() = runTest {
        vault.createVault(notebook, "pass").getOrThrow()
        val dirPath = Path(notebookDir, "old").toString()
        val a = editor.createNote(dirPath, "a", "a-content").getOrThrow()
        val b = editor.createNote(Path(Path(notebookDir, "old"), "deep").toString(), "b", "b-content").getOrThrow()

        editor.renameItem(dirPath, "new").getOrThrow()

        val newDir = Path(notebookDir, "new").toString()
        assertEquals("a-content", editor.readNoteContent(Path(Path(notebookDir, "new"), "a.md").toString()).getOrThrow())
        assertEquals("b-content", editor.readNoteContent(Path(Path(notebookDir, "new"), "deep", "b.md").toString()).getOrThrow())
        assertTrue(editor.fileExists(Path(Path(notebookDir, "new"), "a.md").toString()))
        assertFalse(editor.fileExists(a))
        assertFalse(editor.fileExists(b))
        assertEquals(2, diskFiles().count { it.endsWith(".gwv") })
    }

    @Test
    fun `deleting a virtual folder deletes all notes inside`() = runTest {
        vault.createVault(notebook, "pass").getOrThrow()
        val dirPath = Path(notebookDir, "sub").toString()
        editor.createNote(dirPath, "a", "x").getOrThrow()
        editor.createNote(dirPath, "b", "y").getOrThrow()
        assertEquals(2, diskFiles().count { it.endsWith(".gwv") })

        editor.deleteItem(dirPath).getOrThrow()

        assertEquals(0, diskFiles().count { it.endsWith(".gwv") })
    }

    // --- locked vaults

    @Test
    fun `locked vault rejects content access and lists encrypted names`() = runTest {
        vault.createVault(notebook, "pass").getOrThrow()
        editor.createNote(notebookDir.toString(), "secret", "top secret").getOrThrow()

        vault.forgetKeyOnDevice(notebook)
        vault.refreshStates()
        assertIs<VaultState.Locked>(vault.stateOf(notebook))

        val notePath = Path(notebookDir, "secret.md").toString()
        val readFailure = editor.readNoteContent(notePath).exceptionOrNull()
        assertIs<VaultLockedException>(readFailure)
        assertFalse(editor.fileExists(notePath))
        // the encrypted file, the keystore and the ignore entry are untouched on disk
        assertEquals(3, diskFiles().size)

        val tree = vault.buildFileTree(notebook)
        val node = assertIs<FileNode.File>(tree?.children?.single())
        assertTrue(node.name.endsWith(".gwv"), "locked tree shows the encrypted name")
        assertEquals(node.diskPath, node.path)
    }

    // --- orphans

    @Test
    fun `orphan encrypted files are listed raw and removed by disk path`() = runTest {
        vault.createVault(notebook, "pass").getOrThrow()
        val orphanDisk = Path(notebookDir, "orphanabcd1234.gwv")
        SystemFileSystem.sink(orphanDisk).buffered().use { it.writeString("not a real vault file") }

        val tree = vault.buildFileTree(notebook)
        val files = tree?.children?.filterIsInstance<FileNode.File>() ?: emptyList()
        assertEquals(1, files.size)
        assertEquals("orphanabcd1234.gwv", files.single().name)

        // reading a raw .gwv path is refused, deleting it works on the real file
        assertTrue(editor.readNoteContent(orphanDisk.toString()).isFailure)
        editor.deleteItem(orphanDisk.toString()).getOrThrow()
        assertFalse(SystemFileSystem.exists(orphanDisk))
    }

    // --- images

    @Test
    fun `pasted images are encrypted like notes`() = runTest {
        vault.createVault(notebook, "pass").getOrThrow()
        val bytes = ByteArray(64) { it.toByte() }

        editor.savePastedImage(notebookDir.toString(), bytes, "photo.png").getOrThrow()

        assertFalse(diskFiles().any { it == "photo.png" || it == "images" })
        val session = vault.sessionForPath(notebookDir.toString())!!
        assertContentEquals(bytes, fileStore.read(notebookDir.toString(), session.vault, "images/photo.png"))

        val tree = vault.buildFileTree(notebook)
        val imageNode = tree?.children
            ?.filterIsInstance<FileNode.Directory>()?.single()?.children
            ?.filterIsInstance<FileNode.File>()?.single()
        assertEquals("photo.png", imageNode?.name)
    }

    // --- passphrase lifecycle

    @Test
    fun `changing the passphrase keeps the data readable`() = runTest {
        vault.createVault(notebook, "old-pass").getOrThrow()
        val notePath = editor.createNote(notebookDir.toString(), "note", "data").getOrThrow()

        vault.changePassphrase(notebook, "old-pass", "new-pass").getOrThrow()

        vault.forgetKeyOnDevice(notebook)
        vault.refreshStates()
        assertIs<VaultState.Locked>(vault.stateOf(notebook))

        assertFailsWith<dev.jotalac.feature.vault.crypto.VaultWrongPassphraseException> {
            vault.unlock(notebook, "old-pass").getOrThrow()
        }
        vault.unlock(notebook, "new-pass").getOrThrow()
        assertEquals("data", editor.readNoteContent(notePath).getOrThrow())
    }

    @Test
    fun `recovery key opens the vault without the passphrase`() = runTest {
        vault.createVault(notebook, "pass").getOrThrow()
        val notePath = editor.createNote(notebookDir.toString(), "note", "data").getOrThrow()
        val recovery = vault.recoveryKeyOf(notebook)!!

        vault.forgetKeyOnDevice(notebook)
        vault.refreshStates()
        assertTrue(secureStorage.stored.isEmpty(), "forget removes the cached key")
        assertNull(vault.recoveryKeyOf(notebook))

        vault.unlockWithRecoveryKey(notebook, recovery).getOrThrow()
        assertEquals("data", editor.readNoteContent(notePath).getOrThrow())
    }

    @Test
    fun `the device key cache auto-unlocks on refresh`() = runTest {
        vault.createVault(notebook, "pass").getOrThrow()
        editor.createNote(notebookDir.toString(), "note", "data").getOrThrow()

        vault.lock(notebook)
        assertIs<VaultState.Locked>(vault.stateOf(notebook))

        vault.refreshStates()
        assertIs<VaultState.Unlocked>(vault.stateOf(notebook))
    }

    @Test
    fun `plain notebooks fall through to the plain repository`() = runTest {
        // no keystore anywhere - the decorator must behave exactly like the base implementation
        val notePath = editor.createNote(notebookDir.toString(), "plain", "visible").getOrThrow()
        assertTrue(notePath.endsWith(".md"))
        assertTrue(diskFiles().contains("plain.md"))
        assertEquals("visible", editor.readNoteContent(notePath).getOrThrow())
        assertIs<VaultState.Off>(vault.stateOf(notebook))
    }

    // --- keystore-less devices (the keystore never travels through git)

    /** Removes the local keystore copy - the situation a fresh clone of an encrypted notebook is in. */
    private fun deleteKeystore() {
        Path(notebookDir, VaultLayout.KEYSTORE_DIR).deleteRecursively()
    }

    @Test
    fun `a vault without a local keystore reads as locked with the key id from the note headers`() = runTest {
        vault.createVault(notebook, "pass").getOrThrow()
        editor.createNote(notebookDir.toString(), "note", "data").getOrThrow()

        deleteKeystore()
        vault.forgetKeyOnDevice(notebook)
        vault.refreshStates()

        val locked = assertIs<VaultState.Locked>(vault.stateOf(notebook))
        assertTrue(locked.keyIdHex != null, "the key id comes from the first `.gwv` header")
        assertFalse(locked.hasLocalKeystore)
    }

    @Test
    fun `a GW2 recovery key unlocks a vault without a keystore and creates none`() = runTest {
        vault.createVault(notebook, "pass").getOrThrow()
        val notePath = editor.createNote(notebookDir.toString(), "note", "data").getOrThrow()
        val recovery = vault.recoveryKeyOf(notebook)!!

        deleteKeystore()
        vault.forgetKeyOnDevice(notebook)
        vault.refreshStates()

        vault.unlockWithRecoveryKey(notebook, recovery).getOrThrow()
        assertEquals("data", editor.readNoteContent(notePath).getOrThrow())
        assertFalse(SystemFileSystem.exists(VaultLayout.keystorePath(notebookDir)), "no keystore is created for the new device")
        val unlocked = assertIs<VaultState.Unlocked>(vault.stateOf(notebook))
        assertFalse(unlocked.hasLocalKeystore)
    }

    @Test
    fun `a legacy GW1 key without a keystore is proven by decrypting a note`() = runTest {
        vault.createVault(notebook, "pass").getOrThrow()
        val notePath = editor.createNote(notebookDir.toString(), "note", "data").getOrThrow()
        val masterKey = vault.sessionForPath(notebookDir.toString())!!.vault.keys.masterKey
        val legacy = Base32.encode(masterKey, uppercase = true).chunked(5).joinToString("-", prefix = "GW1-")

        deleteKeystore()
        vault.forgetKeyOnDevice(notebook)
        vault.refreshStates()

        vault.unlockWithRecoveryKey(notebook, legacy).getOrThrow()
        assertEquals("data", editor.readNoteContent(notePath).getOrThrow())
    }

    @Test
    fun `a legacy GW1 key with a wrong master key is rejected when there is no keystore`() = runTest {
        vault.createVault(notebook, "pass").getOrThrow()
        editor.createNote(notebookDir.toString(), "note", "data").getOrThrow()

        deleteKeystore()
        vault.forgetKeyOnDevice(notebook)
        vault.refreshStates()
        val wrong = Base32.encode(ByteArray(32) { 7 }, uppercase = true).chunked(5).joinToString("-", prefix = "GW1-")

        assertFailsWith<VaultFormatException> {
            vault.unlockWithRecoveryKey(notebook, wrong).getOrThrow()
        }
    }

    @Test
    fun `a GW2 key of another vault is rejected when there is no keystore`() = runTest {
        vault.createVault(notebook, "pass").getOrThrow()
        editor.createNote(notebookDir.toString(), "note", "data").getOrThrow()

        deleteKeystore()
        vault.forgetKeyOnDevice(notebook)
        vault.refreshStates()
        val foreign = RecoveryKeyCodec.encode(ByteArray(8) { 3 }, ByteArray(32) { 9 })

        assertFailsWith<VaultFormatException> {
            vault.unlockWithRecoveryKey(notebook, foreign).getOrThrow()
        }
    }

    @Test
    fun `importing a keystore file makes the notebook passphrase-openable again`() = runTest {
        vault.createVault(notebook, "pass").getOrThrow()
        val notePath = editor.createNote(notebookDir.toString(), "note", "data").getOrThrow()
        val keystoreJson = vault.localKeystoreJson(notebook).getOrThrow()!!

        deleteKeystore()
        vault.forgetKeyOnDevice(notebook)
        vault.refreshStates()
        assertNull(vault.localKeystoreJson(notebook).getOrThrow())

        vault.importKeystore(notebook, keystoreJson).getOrThrow()
        vault.refreshStates()

        val locked = assertIs<VaultState.Locked>(vault.stateOf(notebook))
        assertTrue(locked.hasLocalKeystore)
        vault.unlock(notebook, "pass").getOrThrow()
        assertEquals("data", editor.readNoteContent(notePath).getOrThrow())
        // the imported copy is not on its way into git either
        val gitignore = SystemFileSystem.source(Path(notebookDir, ".gitignore")).buffered().use { it.readString() }
        assertTrue(".gw-vault/" in gitignore, "expected the vault entry in: $gitignore")
    }

    @Test
    fun `importing a keystore of another key over an existing one is refused`() = runTest {
        vault.createVault(notebook, "pass").getOrThrow()
        val originalJson = vault.localKeystoreJson(notebook).getOrThrow()!!

        val otherDir = Path(SystemTemporaryDirectory, "git_writer_vault_other_${Random.nextInt()}")
        SystemFileSystem.createDirectories(otherDir)
        val other = Notebook(2, "Other", otherDir.toString(), null, null, null)
        notebooks.notebooks.value = listOf(notebook, other)
        try {
            vault.createVault(other, "other-pass").getOrThrow()
            val otherJson = vault.localKeystoreJson(other).getOrThrow()!!

            assertFailsWith<VaultKeystoreConflictException> {
                vault.importKeystore(notebook, otherJson).getOrThrow()
            }
            assertEquals(originalJson, vault.localKeystoreJson(notebook).getOrThrow())
        } finally {
            otherDir.deleteRecursively()
        }
    }

    @Test
    fun `importing a broken keystore file is refused`() = runTest {
        vault.createVault(notebook, "pass").getOrThrow()

        assertFailsWith<VaultFormatException> {
            vault.importKeystore(notebook, "not a keystore").getOrThrow()
        }
    }
}
