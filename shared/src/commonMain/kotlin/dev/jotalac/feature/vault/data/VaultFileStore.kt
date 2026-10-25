package dev.jotalac.feature.vault.data

import dev.jotalac.feature.vault.crypto.UnlockedVault
import dev.jotalac.feature.vault.crypto.VaultFileCodec
import dev.jotalac.feature.vault.crypto.canonicalRelPath
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.writeString

/**
 * Note content I/O on top of the encrypted files: maps a canonical relative note path to its
 * `<cname>.gwv` file and decrypts/encrypts on the way. Writes are atomic - a half-written
 * ciphertext would lose the note (there is no partially readable plaintext to fall back to).
 */
class VaultFileStore(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    fun diskPath(root: String, vault: UnlockedVault, relPath: String): Path =
        Path(root, vault.fileNameOf(relPath))

    fun exists(root: String, vault: UnlockedVault, relPath: String): Boolean =
        SystemFileSystem.exists(diskPath(root, vault, relPath))

    /** @return the decrypted content, null when the note does not exist on disk */
    suspend fun read(root: String, vault: UnlockedVault, relPath: String): ByteArray? = withContext(ioDispatcher) {
        val disk = diskPath(root, vault, relPath)
        if (!SystemFileSystem.exists(disk)) return@withContext null
        val encoded = SystemFileSystem.source(disk).buffered().use { it.readByteArray() }
        VaultFileCodec.decode(encoded, vault, vault.cnameOf(relPath)).content
    }

    suspend fun write(root: String, vault: UnlockedVault, relPath: String, content: ByteArray): Unit = withContext(ioDispatcher) {
        val encoded = VaultFileCodec.encode(vault, relPath, content)
        val disk = diskPath(root, vault, relPath)
        // the temp file lives in .gw-vault so a crash leftover is hidden from the file tree;
        // it is moved onto the target (or deleted) before this call returns
        val tmp = Path(Path(root, VaultLayout.KEYSTORE_DIR), "tmp-" + disk.name)
        try {
            SystemFileSystem.sink(tmp).buffered().use { it.write(encoded) }
            try {
                SystemFileSystem.atomicMove(tmp, disk)
            } catch (e: IOException) {
                // atomic move unsupported on this file system - write in place
                SystemFileSystem.sink(disk).buffered().use { it.write(encoded) }
            }
        } finally {
            if (SystemFileSystem.exists(tmp)) SystemFileSystem.delete(tmp)
        }
    }

    suspend fun delete(root: String, vault: UnlockedVault, relPath: String): Unit = withContext(ioDispatcher) {
        SystemFileSystem.delete(diskPath(root, vault, relPath), mustExist = false)
    }

    /**
     * Moves a note to a new relative path without decrypting it into memory twice: decode,
     * re-encode under the new path (the path lives inside the encrypted payload), write, delete.
     */
    suspend fun move(root: String, vault: UnlockedVault, fromRel: String, toRel: String): Unit = withContext(ioDispatcher) {
        val content = read(root, vault, fromRel) ?: throw IOException("Note not found: $fromRel")
        write(root, vault, toRel, content)
        delete(root, vault, fromRel)
    }

    /**
     * The canonical relative paths of all decryptable notes in the notebook. Undecryptable
     * files (orphans) are skipped - they have no meaningful virtual path.
     */
    suspend fun listNoteRelPaths(root: String, vault: UnlockedVault): List<String> = withContext(ioDispatcher) {
        val rootPath = Path(root)
        if (!SystemFileSystem.exists(rootPath)) return@withContext emptyList()
        SystemFileSystem.list(rootPath)
            .filter { it.name.endsWith(VaultLayout.ENCRYPTED_EXTENSION) }
            .mapNotNull { disk ->
                try {
                    val encoded = SystemFileSystem.source(disk).buffered().use { it.readByteArray() }
                    VaultFileCodec.decode(encoded, vault, disk.name.removeSuffix(VaultLayout.ENCRYPTED_EXTENSION)).relPath
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    null // orphan (wrong key id, corrupted, ...) - no virtual path to report
                }
            }
    }

    /** Validates the path; returns the canonical relative path. */
    fun requireRelPath(root: String, vault: UnlockedVault, fullPath: String): String {
        val relative = VaultLayout.relPathOf(root, fullPath)
        require(relative.isNotEmpty()) { "Expected a note path, got the notebook root" }
        return canonicalRelPath(relative)
    }
}
