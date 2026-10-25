package dev.jotalac.feature.vault.crypto

/** The vault key id embedded in every encrypted file header and in the keystore. */
class UnlockedVault(val keyId: ByteArray, val keys: VaultKeys) {

    companion object {
        const val KEY_ID_BYTES = 8
    }

    init {
        require(keyId.size == KEY_ID_BYTES) { "Vault key id must be $KEY_ID_BYTES bytes" }
    }

    fun cnameOf(rawRelPath: String): String = keys.cnameOf(rawRelPath)

    /** The on-disk name of the encrypted file for a note path (flat in the notebook root). */
    fun fileNameOf(rawRelPath: String): String = cnameOf(rawRelPath) + VaultFileCodec.FILE_EXTENSION
}
