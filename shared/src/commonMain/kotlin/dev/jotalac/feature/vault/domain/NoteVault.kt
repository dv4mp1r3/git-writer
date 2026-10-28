package dev.jotalac.feature.vault.domain

import dev.jotalac.feature.editor_sidebar.domain.FileNode
import dev.jotalac.feature.notebooks_management.domain.Notebook
import dev.jotalac.feature.vault.crypto.OpenedVault
import dev.jotalac.feature.vault.crypto.UnlockedVault
import kotlinx.coroutines.flow.StateFlow

/** Vault state of one notebook, tracked by [NoteVault.states]. */
sealed class VaultState {
    /** No `.gw-vault/keystore` in the notebook folder - notes are plain. */
    data object Off : VaultState()

    /** The keystore is present and its master key is available (entered or cached). */
    data class Unlocked(val keyIdHex: String) : VaultState()

    /** The keystore is present but the master key is not known on this device (yet). */
    data object Locked : VaultState()
}

/** Thrown (wrapped in Result.failure) when a note in a locked vault is requested. */
class VaultLockedException(message: String = "The notebook is encrypted and locked") : Exception(message)

/**
 * Per-notebook encrypted-storage manager. Owns the in-memory unlocked sessions and the vault
 * file-tree listing; note content I/O goes through the [dev.jotalac.feature.editor.domain.EditorRepository]
 * decorator which resolves paths against these sessions.
 */
interface NoteVault {

    /** Current vault state per notebook id. */
    val states: StateFlow<Map<Long, VaultState>>

    fun stateOf(notebook: Notebook): VaultState

    /** Re-scans notebook folders for `.gw-vault/keystore` and tries the device key cache. */
    suspend fun refreshStates()

    /**
     * Unwraps the master key with the passphrase (a recovery key string is NOT accepted here -
     * see [unlockWithRecoveryKey]) and caches it in the device secure storage.
     */
    suspend fun unlock(notebook: Notebook, passphrase: String): Result<Unit>

    /** Opens a vault whose passphrase was lost; the recovery key string decodes to the master key. */
    suspend fun unlockWithRecoveryKey(notebook: Notebook, recoveryKey: String): Result<Unit>

    /** Drops the in-memory session; the device key cache is kept. */
    suspend fun lock(notebook: Notebook)

    /** Drops the in-memory session and removes the master key from this device's key cache. */
    suspend fun forgetKeyOnDevice(notebook: Notebook)

    /**
     * Creates a fresh vault: generates the master key, writes `.gw-vault/keystore` into the
     * notebook folder and keeps the session unlocked. Encrypting the existing notes is a
     * separate step (the enable flow in the UI layer).
     */
    suspend fun createVault(notebook: Notebook, passphrase: String): Result<Unit>

    /** Re-wraps the master key under a new passphrase; encrypted data is not re-encrypted. */
    suspend fun changePassphrase(notebook: Notebook, currentPassphrase: String, newPassphrase: String): Result<Unit>

    /** The recovery key string; [currentPassphrase] guards the export. */
    suspend fun exportRecoveryKey(notebook: Notebook, currentPassphrase: String): Result<String>

    /** The recovery key of an unlocked vault; null when the notebook is not an unlocked vault. */
    fun recoveryKeyOf(notebook: Notebook): String?

    /**
     * The file tree of a vault notebook built from the decrypted headers: path is the virtual
     * note path, diskPath points to the encrypted file. Locked vaults list raw `.gwv` names
     * (orphans included); undecryptable files in an unlocked vault are listed the same way.
     */
    suspend fun buildFileTree(notebook: Notebook): FileNode.Directory?

    /** The session for an unlocked notebook folder, null otherwise (used by the image loader). */
    fun sessionForPath(directoryPath: String): OpenedVault?
}
