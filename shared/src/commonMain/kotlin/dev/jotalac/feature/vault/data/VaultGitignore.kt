package dev.jotalac.feature.vault.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.writeString

/**
 * `.gitignore` bookkeeping that keeps the keystore out of the notes repository: the keystore
 * wraps the master key with the passphrase, so it is device-local and must never be committed.
 */
const val VAULT_GITIGNORE_ENTRY = "${VaultLayout.KEYSTORE_DIR}/"

/**
 * Writes or appends [VAULT_GITIGNORE_ENTRY] into `<root>/.gitignore`; the user's own entries
 * are kept as-is. Also hides the `.gw-vault` temp files written during atomic note saves.
 * @return true when the file content changed.
 */
suspend fun ensureKeystoreIgnored(root: String): Boolean = withContext(Dispatchers.IO) {
    val path = Path(root, ".gitignore")
    val current = if (SystemFileSystem.exists(path)) {
        SystemFileSystem.source(path).buffered().use { it.readByteArray() }.decodeToString()
    } else {
        ""
    }
    if (current.lineSequence().any { it.trim() == VAULT_GITIGNORE_ENTRY }) return@withContext false
    val updated = when {
        current.isEmpty() -> "$VAULT_GITIGNORE_ENTRY\n"
        current.endsWith("\n") -> current + VAULT_GITIGNORE_ENTRY + "\n"
        else -> current + "\n" + VAULT_GITIGNORE_ENTRY + "\n"
    }
    SystemFileSystem.sink(path).buffered().use { it.writeString(updated) }
    true
}
