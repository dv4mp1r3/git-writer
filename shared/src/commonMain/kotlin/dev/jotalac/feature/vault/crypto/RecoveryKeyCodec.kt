package dev.jotalac.feature.vault.crypto

/**
 * The master key rendered as a human-copyable string. It IS the master key - showing it requires
 * the passphrase, entering it replaces the passphrase on a new device. Case- and
 * separator-tolerant on input.
 *
 * - `GW1-xxxxx-…` (legacy): 52 base32 chars - the raw master key. The key id is not part of the
 *   string, so it needs the local keystore (or a `.gwv` header) to name the vault it opens.
 * - `GW2-xxxxx-…`: 64 base32 chars of `key id (8) || master key (32)` - self-sufficient, opens a
 *   vault whose keystore never reached this device.
 */
object RecoveryKeyCodec {

    const val PREFIX_V1 = "GW1"
    const val PREFIX_V2 = "GW2"
    private const val MASTER_KEY_BYTES = VaultKeys.MASTER_KEY_BYTES
    private const val PAYLOAD_V2_BYTES = UnlockedVault.KEY_ID_BYTES + MASTER_KEY_BYTES

    /** A decoded recovery key; [keyIdHex] is null for the legacy GW1 format. */
    class Decoded(val keyIdHex: String?, val masterKey: ByteArray)

    fun encode(keyId: ByteArray, masterKey: ByteArray): String {
        require(keyId.size == UnlockedVault.KEY_ID_BYTES) { "Key id must be ${UnlockedVault.KEY_ID_BYTES} bytes" }
        require(masterKey.size == MASTER_KEY_BYTES) { "Master key must be $MASTER_KEY_BYTES bytes" }
        val payload = keyId + masterKey
        return Base32.encode(payload, uppercase = true).chunked(5).joinToString("-", prefix = "$PREFIX_V2-")
    }

    /** @throws VaultFormatException when the text is not a well-formed recovery key */
    fun decode(text: String): Decoded {
        val tokens = text.trim().split('-', ' ', '\t', '\n').filter { it.isNotBlank() }
        if (tokens.isEmpty()) throw VaultFormatException("Not a recovery key (empty input)")
        val isV2 = tokens.first().equals(PREFIX_V2, ignoreCase = true)
        if (!isV2 && !tokens.first().equals(PREFIX_V1, ignoreCase = true)) {
            throw VaultFormatException("Not a recovery key (missing $PREFIX_V1/$PREFIX_V2 prefix)")
        }
        val expectedBytes = if (isV2) PAYLOAD_V2_BYTES else MASTER_KEY_BYTES
        val bytes = Base32.decode(tokens.drop(1).joinToString(""))
        if (bytes.size != expectedBytes) {
            throw VaultFormatException("Recovery key must decode to $expectedBytes bytes, got ${bytes.size}")
        }
        if (isV2) {
            val keyId = bytes.copyOfRange(0, UnlockedVault.KEY_ID_BYTES)
            val masterKey = bytes.copyOfRange(UnlockedVault.KEY_ID_BYTES, bytes.size)
            return Decoded(Hex.encode(keyId), masterKey)
        }
        return Decoded(keyIdHex = null, masterKey = bytes)
    }
}
