package dev.jotalac.feature.vault.crypto

/**
 * The master key rendered as a human-copyable string: `GW1-xxxxx-xxxxx-…` (52 base32 chars in
 * groups of 5). It IS the master key - showing it requires the passphrase, entering it replaces
 * the passphrase on a new device. Case- and separator-tolerant on input.
 */
object RecoveryKeyCodec {

    private const val PREFIX = "GW1"
    private const val MASTER_KEY_BYTES = VaultKeys.MASTER_KEY_BYTES

    fun encode(masterKey: ByteArray): String {
        require(masterKey.size == MASTER_KEY_BYTES) { "Master key must be $MASTER_KEY_BYTES bytes" }
        return Base32.encode(masterKey, uppercase = true).chunked(5).joinToString("-", prefix = "$PREFIX-")
    }

    /** @throws VaultFormatException when the text is not a well-formed recovery key */
    fun decode(text: String): ByteArray {
        val tokens = text.trim().split('-', ' ', '\t', '\n').filter { it.isNotBlank() }
        if (tokens.isEmpty() || !tokens.first().equals(PREFIX, ignoreCase = true)) {
            throw VaultFormatException("Not a recovery key (missing $PREFIX prefix)")
        }
        val masterKey = Base32.decode(tokens.drop(1).joinToString(""))
        if (masterKey.size != MASTER_KEY_BYTES) {
            throw VaultFormatException("Recovery key must decode to $MASTER_KEY_BYTES bytes, got ${masterKey.size}")
        }
        return masterKey
    }
}
