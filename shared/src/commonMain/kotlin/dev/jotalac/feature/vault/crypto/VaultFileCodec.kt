@file:OptIn(kotlin.ExperimentalUnsignedTypes::class)

package dev.jotalac.feature.vault.crypto

/**
 * Binary container for one encrypted file:
 *
 *   "GWVLT1\n" (7) | version u8 (1) | key id (8) | nonce (24) |
 *   XChaCha20-Poly1305( u16be pathLen | canonical relPath utf-8 | content,
 *                      AAD = magic|version|key id|cname )
 *
 * The real path lives inside the authenticated region (that is how the file tree is listed),
 * and the on-disk name is bound via the AAD, so a blob copied under another file's name fails
 * to decrypt. Files sit flat in the notebook root next to .git.
 */
object VaultFileCodec {

    const val FILE_EXTENSION = ".gwv"
    const val VERSION: Int = 1
    val MAGIC: ByteArray = "GWVLT1\n".encodeToByteArray()
    const val KEY_ID_BYTES = UnlockedVault.KEY_ID_BYTES
    val HEADER_BYTES: Int = MAGIC.size + 1 + KEY_ID_BYTES + VaultCrypto.NONCE_BYTES

    /** Decrypts to this. */
    class DecodedVaultFile(val relPath: String, val content: ByteArray)

    suspend fun encode(vault: UnlockedVault, rawRelPath: String, content: ByteArray): ByteArray =
        encode(vault, rawRelPath, content, VaultCrypto.generateNonce().toByteArray())

    /** Deterministic variant used by the known-answer tests. */
    internal suspend fun encode(vault: UnlockedVault, rawRelPath: String, content: ByteArray, nonce: ByteArray): ByteArray {
        require(nonce.size == VaultCrypto.NONCE_BYTES) { "Nonce must be ${VaultCrypto.NONCE_BYTES} bytes" }
        val canonical = canonicalRelPath(rawRelPath)
        val pathBytes = canonical.encodeToByteArray()
        if (pathBytes.size > 0xFFFF) throw VaultFormatException("Note path too long: ${canonical.length} chars")
        val payload = ByteArray(2 + pathBytes.size + content.size)
        payload[0] = (pathBytes.size ushr 8).toByte()
        payload[1] = (pathBytes.size and 0xFF).toByte()
        pathBytes.copyInto(payload, 2)
        content.copyInto(payload, 2 + pathBytes.size)
        val ciphertext = VaultCrypto.aeadEncrypt(vault.keys.contentKey, nonce, payload, aadFor(vault.keyId, vault.cnameOf(canonical)))
        val out = ByteArray(HEADER_BYTES + ciphertext.size)
        MAGIC.copyInto(out)
        out[MAGIC.size] = VERSION.toByte()
        vault.keyId.copyInto(out, MAGIC.size + 1)
        nonce.copyInto(out, MAGIC.size + 1 + KEY_ID_BYTES)
        ciphertext.copyInto(out, HEADER_BYTES)
        return out
    }

    /**
     * @param cname the encrypted base name this file is stored under, without [FILE_EXTENSION]
     * @throws VaultFormatException on a malformed header or key id mismatch
     * @throws VaultTamperedDataException when authentication fails (wrong key, edits, swapped blobs)
     */
    suspend fun decode(data: ByteArray, vault: UnlockedVault, cname: String): DecodedVaultFile {
        if (data.size < HEADER_BYTES + 2 + 16) throw VaultFormatException("Vault file too short (${data.size} bytes)")
        for (i in MAGIC.indices) {
            if (data[i] != MAGIC[i]) throw VaultFormatException("Not a vault file (bad magic)")
        }
        val version = data[MAGIC.size].toInt() and 0xFF
        if (version != VERSION) throw VaultFormatException("Unsupported vault file version: $version")
        val keyId = data.copyOfRange(MAGIC.size + 1, MAGIC.size + 1 + KEY_ID_BYTES)
        if (!keyId.contentEquals(vault.keyId)) throw VaultFormatException("Vault key id mismatch")
        val nonce = data.copyOfRange(MAGIC.size + 1 + KEY_ID_BYTES, HEADER_BYTES)
        val payload = VaultCrypto.aeadDecrypt(
            key = vault.keys.contentKey,
            nonce = nonce,
            ciphertext = data.copyOfRange(HEADER_BYTES, data.size),
            additionalData = aadFor(vault.keyId, cname),
        )
        val pathLen = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        if (2 + pathLen > payload.size) throw VaultFormatException("Corrupt path length in vault file")
        val relPath = payload.decodeToString(2, 2 + pathLen)
        val content = payload.copyOfRange(2 + pathLen, payload.size)
        return DecodedVaultFile(relPath, content)
    }

    private fun aadFor(keyId: ByteArray, cname: String): ByteArray {
        val aad = ByteArray(MAGIC.size + 1 + keyId.size + cname.length)
        MAGIC.copyInto(aad)
        aad[MAGIC.size] = VERSION.toByte()
        keyId.copyInto(aad, MAGIC.size + 1)
        cname.encodeToByteArray().copyInto(aad, MAGIC.size + 1 + keyId.size)
        return aad
    }
}
