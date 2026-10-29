package dev.jotalac.feature.vault.data

import dev.jotalac.core.utils.ALLOWED_FILE_EXTENSIONS
import dev.jotalac.core.utils.deleteRecursively
import dev.jotalac.core.utils.suspendRunCatching
import dev.jotalac.feature.git_sync.domain.GitSyncRepository
import dev.jotalac.feature.notebooks_management.domain.Notebook
import dev.jotalac.feature.vault.crypto.OpenedVault
import dev.jotalac.feature.vault.crypto.canonicalRelPath
import dev.jotalac.feature.vault.domain.NoteVault
import dev.jotalac.feature.vault.domain.VaultLockedException
import dev.jotalac.feature.vault.domain.VaultState
import kotlinx.coroutines.CancellationException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.writeString

/**
 * The encryption on/off flows of a notebook: they combine the conversion of the notes with the git
 * history rewrite, so the host is left without a readable commit. Enable encrypts every note and
 * replaces the history with one commit of ciphertext, force-pushed over the old one. Disable is the
 * mirror image. Adopt catches a device up with a notebook that another device has encrypted in the
 * meantime: the local notes are taken out of the way, the remote state is restored, and the local
 * notes come back as encrypted files.
 */
class VaultController(
    private val git: GitSyncRepository,
    private val vault: NoteVault,
    private val fileStore: VaultFileStore,
) {

    /**
     * Turns encryption on. A run that failed halfway can be repeated: the notes that are already
     * encrypted stay that way, the rest is encrypted, and the history rewrite is done from scratch.
     */
    suspend fun enableEncryption(notebook: Notebook, passphrase: String): Result<Unit> = suspendRunCatching {
        val dir = notebook.directoryPath
        val password = notebook.remotePassword
        val hasGit = isGitNotebook(dir)
        val hasRemote = hasGit && !notebook.remoteUrl.isNullOrBlank() && password != null
        // from this moment every commit of the notebook is anonymous
        if (hasRemote) git.setRepoIdentity(dir).getOrThrow()

        if (vault.stateOf(notebook) is VaultState.Off && hasRemote) {
            // a stale clone of an already encrypted notebook has no `.gwv` files yet and reads
            // as Off - syncing brings them in and flips the state to Locked, so no second key
            // can be minted over the first one
            git.syncNotes(dir, password!!, notebook.remoteUsername).getOrThrow()
        }

        if (vault.stateOf(notebook) is VaultState.Off) {
            vault.createVault(notebook, passphrase).getOrThrow()
            encryptAllNotes(notebook).getOrThrow()
        } else if (listPlaintextRelPaths(dir).isNotEmpty()) {
            encryptAllNotes(notebook).getOrThrow()
        }

        // the keystore is device-local - the ignore entry must exist before the history rewrite
        ensureKeystoreIgnored(dir)

        if (hasGit) git.resetHistoryToCurrentState(dir, ENCRYPT_MESSAGE).getOrThrow()
        if (hasRemote) git.forcePush(dir, password!!, notebook.remoteUsername).getOrThrow()
        vault.refreshStates()
    }

    /** Turns encryption off; the notebook has to be unlocked ([dev.jotalac.feature.vault.domain.NoteVault.unlock]). */
    suspend fun disableEncryption(notebook: Notebook): Result<Unit> = suspendRunCatching {
        val dir = notebook.directoryPath
        val password = notebook.remotePassword
        val hasGit = isGitNotebook(dir)
        val hasRemote = hasGit && !notebook.remoteUrl.isNullOrBlank() && password != null

        decryptAllNotes(notebook).getOrThrow()
        if (hasGit) git.resetHistoryToCurrentState(dir, DECRYPT_MESSAGE).getOrThrow()
        if (hasRemote) git.forcePush(dir, password!!, notebook.remoteUsername).getOrThrow()
        vault.forgetKeyOnDevice(notebook)
        vault.refreshStates()
    }

    /**
     * Unlocks a notebook that another device has encrypted and brings it to the remote state. The
     * plain notes still lying in the folder are saved first, the hard reset replaces the worktree
     * with the encrypted remote state, and the saved notes are written back as encrypted files.
     */
    suspend fun adoptEncryptedNotebook(notebook: Notebook, passphrase: String): Result<Unit> =
        adopt(notebook) { vault.unlock(notebook, passphrase) }

    /** The same, when the passphrase was lost and the recovery key is what is left. */
    suspend fun adoptEncryptedNotebookWithRecoveryKey(notebook: Notebook, recoveryKey: String): Result<Unit> =
        adopt(notebook) { vault.unlockWithRecoveryKey(notebook, recoveryKey) }

    /**
     * Replaces the notebook's history with one commit of the current tree and force-pushes it, so
     * the keystore the history still holds disappears from the repository (the local file stays).
     * Offered after the automatic keystore untracking - history is the only place it can survive.
     */
    suspend fun purgeVaultHistory(notebook: Notebook): Result<Unit> = suspendRunCatching {
        val dir = notebook.directoryPath
        val password = notebook.remotePassword
        val hasGit = isGitNotebook(dir)
        val hasRemote = hasGit && !notebook.remoteUrl.isNullOrBlank() && password != null
        if (!hasGit) return@suspendRunCatching

        ensureKeystoreIgnored(dir)
        // from this moment every commit of the notebook is anonymous
        if (hasRemote) git.setRepoIdentity(dir).getOrThrow()
        git.resetHistoryToCurrentState(dir, PURGE_MESSAGE).getOrThrow()
        if (hasRemote) git.forcePush(dir, password!!, notebook.remoteUsername).getOrThrow()
    }

    /** Unlocks a notebook that is already on the encrypted remote state - no git work to do. */
    suspend fun unlockEncryptedNotebook(notebook: Notebook, passphrase: String): Result<Unit> =
        adopt(notebook) { vault.unlock(notebook, passphrase) }

    /** The canonical relative paths of the plain, not yet encrypted notes in the folder. */
    fun listPlaintextRelPaths(root: String): List<String> {
        val rootPath = Path(root)
        return walkFiles(rootPath)
            .filter { file -> ALLOWED_FILE_EXTENSIONS.any { file.name.lowercase().endsWith(it) } }
            .mapNotNull { file ->
                try {
                    canonicalRelPath(relPathOf(rootPath, file))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null // not a name a note can have - not our file to touch
                }
            }
    }

    /** True when the folder holds plain notes next to a vault - a device that has not caught up yet. */
    fun hasPlaintextNotes(notebook: Notebook): Boolean =
        vault.stateOf(notebook) !is VaultState.Off && listPlaintextRelPaths(notebook.directoryPath).isNotEmpty()

    /** Encrypts every plain note and deletes the plaintext. Requires an unlocked session. */
    private suspend fun encryptAllNotes(notebook: Notebook): Result<Unit> = suspendRunCatching {
        val root = notebook.directoryPath
        val session = vault.sessionForPath(root) ?: throw VaultLockedException()
        for (rel in listPlaintextRelPaths(root)) {
            val plain = Path(VaultLayout.joinVirtual(root, rel))
            val bytes = SystemFileSystem.source(plain).buffered().use { it.readByteArray() }
            fileStore.write(root, session.vault, rel, bytes)
            SystemFileSystem.delete(plain)
        }
        removeEmptyDirs(Path(root), Path(root))
    }

    /** Decodes every encrypted note back to its plain path and removes the vault. Requires an unlocked session. */
    private suspend fun decryptAllNotes(notebook: Notebook): Result<Unit> = suspendRunCatching {
        val root = notebook.directoryPath
        val session = vault.sessionForPath(root) ?: throw VaultLockedException()
        for (rel in fileStore.listNoteRelPaths(root, session.vault)) {
            val bytes = fileStore.read(root, session.vault, rel) ?: continue
            val plain = Path(VaultLayout.joinVirtual(root, rel))
            plain.parent?.let { SystemFileSystem.createDirectories(it) }
            SystemFileSystem.sink(plain).buffered().use { it.write(bytes) }
            fileStore.delete(root, session.vault, rel)
        }
        // undecryptable leftovers have no key to come back for
        SystemFileSystem.list(Path(root))
            .filter { it.name.endsWith(VaultLayout.ENCRYPTED_EXTENSION) }
            .forEach { SystemFileSystem.delete(it, mustExist = false) }
        Path(root, VaultLayout.KEYSTORE_DIR).deleteRecursively()
    }

    private suspend fun adopt(notebook: Notebook, unlock: suspend () -> Result<Unit>): Result<Unit> = suspendRunCatching {
        val dir = notebook.directoryPath
        val password = notebook.remotePassword
        val hasGit = isGitNotebook(dir)
        val hasRemote = hasGit && !notebook.remoteUrl.isNullOrBlank() && password != null

        unlock().getOrThrow()
        if (!hasRemote) {
            vault.refreshStates()
            return@suspendRunCatching
        }

        val session = vault.sessionForPath(dir) ?: throw VaultLockedException()
        val saved = readPlaintextNotes(dir)
        if (saved.isNotEmpty()) {
            // the plain notes are not part of the remote history - take them out of the reset's way
            git.resetToRemote(dir, password!!, notebook.remoteUsername).getOrThrow()
            for ((rel, bytes) in saved) {
                fileStore.write(dir, session.vault, freeRelPath(dir, session, rel), bytes)
                // a hard reset keeps untracked files - the plain note goes away explicitly
                SystemFileSystem.delete(Path(VaultLayout.joinVirtual(dir, rel)))
            }
            removeEmptyDirs(Path(dir), Path(dir))
            git.syncNotes(dir, password, notebook.remoteUsername, ADOPT_MESSAGE).getOrThrow()
        }
        vault.refreshStates()
    }

    /** The plain notes as rel path -> content, read before the worktree gets reset. */
    private suspend fun readPlaintextNotes(root: String): Map<String, ByteArray> {
        val notes = mutableMapOf<String, ByteArray>()
        for (rel in listPlaintextRelPaths(root)) {
            val plain = Path(VaultLayout.joinVirtual(root, rel))
            notes[rel] = SystemFileSystem.source(plain).buffered().use { it.readByteArray() }
        }
        return notes
    }

    /** [rel], or "name 1.ext" style next to it when the remote already has a note with that name. */
    private fun freeRelPath(root: String, session: OpenedVault, rel: String): String {
        if (!fileStore.exists(root, session.vault, rel)) return rel
        val dir = rel.substringBeforeLast('/', missingDelimiterValue = "")
        val name = rel.substringAfterLast('/')
        val base = name.substringBeforeLast('.')
        val extension = name.substringAfterLast('.', missingDelimiterValue = "").let { if (it.isEmpty()) "" else ".$it" }
        var counter = 1
        while (true) {
            val candidate = (if (dir.isEmpty()) "" else "$dir/") + "$base $counter$extension"
            if (!fileStore.exists(root, session.vault, candidate)) return candidate
            counter++
        }
    }

    private fun isGitNotebook(dir: String): Boolean =
        SystemFileSystem.metadataOrNull(Path(dir, ".git")) != null

    private fun walkFiles(dir: Path): List<Path> {
        if (SystemFileSystem.metadataOrNull(dir)?.isDirectory != true) return emptyList()
        val files = mutableListOf<Path>()
        for (child in SystemFileSystem.list(dir)) {
            if (child.name == ".git" || child.name == VaultLayout.KEYSTORE_DIR) continue
            if (SystemFileSystem.metadataOrNull(child)?.isDirectory == true) files += walkFiles(child)
            else files += child
        }
        return files
    }

    /** The folders the encryption emptied out; a best effort - non-empty ones refuse and stay. */
    private fun removeEmptyDirs(dir: Path, root: Path) {
        if (SystemFileSystem.metadataOrNull(dir)?.isDirectory != true) return
        for (child in SystemFileSystem.list(dir)) {
            if (child.name == ".git" || child.name == VaultLayout.KEYSTORE_DIR) continue
            if (SystemFileSystem.metadataOrNull(child)?.isDirectory == true) removeEmptyDirs(child, root)
        }
        if (dir != root) runCatching { SystemFileSystem.delete(dir, mustExist = false) }
    }

    private fun relPathOf(root: Path, file: Path): String =
        file.toString().removePrefix(root.toString()).trimStart('/', '\\').replace('\\', '/')

    private companion object {
        const val ENCRYPT_MESSAGE = "Encrypt notes"
        const val DECRYPT_MESSAGE = "Decrypt notes"
        const val ADOPT_MESSAGE = "Restore notes after encryption"
        const val PURGE_MESSAGE = "Remove the vault keystore from history"
    }
}
