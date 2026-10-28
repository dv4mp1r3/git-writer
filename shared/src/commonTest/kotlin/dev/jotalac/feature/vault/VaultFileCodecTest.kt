package dev.jotalac.feature.vault

import dev.jotalac.feature.vault.crypto.Hex
import dev.jotalac.feature.vault.crypto.UnlockedVault
import dev.jotalac.feature.vault.crypto.VaultCrypto
import dev.jotalac.feature.vault.crypto.VaultFileCodec
import dev.jotalac.feature.vault.crypto.VaultFormatException
import dev.jotalac.feature.vault.crypto.VaultKeys
import dev.jotalac.feature.vault.crypto.VaultTamperedDataException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VaultFileCodecTest {

    private suspend fun fixedVault(): UnlockedVault {
        VaultCrypto.init()
        val keys = VaultKeys.fromMasterKey(ByteArray(32) { (it * 7 + 1).toByte() })
        return UnlockedVault(ByteArray(8) { (it + 7).toByte() }, keys)
    }

    private fun fixedNonce(): ByteArray = ByteArray(24) { (it * 3 + 2).toByte() }

    @Test
    fun roundtrip_with_unicode_path_and_binary_content() = runTest {
        val vault = fixedVault()
        val content = ByteArray(1024) { (it * 97 + 13).toByte() } // includes zero bytes, "binary" data
        val encoded = VaultFileCodec.encode(vault, "Дневник/2025/Заметка №1.md", content)
        val decoded = VaultFileCodec.decode(encoded, vault, vault.cnameOf("Дневник/2025/Заметка №1.md"))
        assertEquals("Дневник/2025/Заметка №1.md", decoded.relPath)
        assertContentEquals(content, decoded.content)
    }

    @Test
    fun header_layout_is_stable() = runTest {
        val vault = fixedVault()
        val nonce = fixedNonce()
        val encoded = VaultFileCodec.encode(vault, "a.md", byteArrayOf(1), nonce)
        assertEquals("GWVLT1\n", encoded.decodeToString(0, 7))
        assertEquals(1, encoded[7].toInt())
        assertContentEquals(vault.keyId, encoded.copyOfRange(8, 16))
        assertContentEquals(nonce, encoded.copyOfRange(16, 40))
        assertEquals(40, VaultFileCodec.HEADER_BYTES)
    }

    @Test
    fun decode_rejects_swapped_blob_between_files() = runTest {
        val vault = fixedVault()
        val fileA = VaultFileCodec.encode(vault, "a.md", byteArrayOf(1, 2, 3), fixedNonce())
        // blob of a.md presented under cname of b.md - the AAD binding must catch it
        assertFailsWith<VaultTamperedDataException> {
            VaultFileCodec.decode(fileA, vault, vault.cnameOf("b.md"))
        }
    }

    @Test
    fun decode_rejects_wrong_key_id() = runTest {
        val vault = fixedVault()
        val encoded = VaultFileCodec.encode(vault, "a.md", byteArrayOf(1), fixedNonce())
        val otherVault = UnlockedVault(ByteArray(8) { 9 }, VaultKeys.fromMasterKey(ByteArray(32) { 1 }))
        assertFailsWith<VaultFormatException> { VaultFileCodec.decode(encoded, otherVault, "doesnotmatter") }
    }

    @Test
    fun decode_rejects_corrupted_bytes() = runTest {
        val vault = fixedVault()
        val encoded = VaultFileCodec.encode(vault, "a.md", ByteArray(500) { it.toByte() }, fixedNonce())
        // corrupt the first ciphertext byte
        val corrupted = encoded.copyOf().also { it[VaultFileCodec.HEADER_BYTES] = (it[VaultFileCodec.HEADER_BYTES] + 1).toByte() }
        assertFailsWith<VaultTamperedDataException> { VaultFileCodec.decode(corrupted, vault, vault.cnameOf("a.md")) }
        // corrupt a byte deep inside the content region
        val corruptedTail = encoded.copyOf().also { it[encoded.size - 1] = (it[encoded.size - 1] + 1).toByte() }
        assertFailsWith<VaultTamperedDataException> { VaultFileCodec.decode(corruptedTail, vault, vault.cnameOf("a.md")) }
    }

    @Test
    fun decode_rejects_malformed_input() = runTest {
        val vault = fixedVault()
        assertFailsWith<VaultFormatException> { VaultFileCodec.decode(ByteArray(10), vault, "x") }
        assertFailsWith<VaultFormatException> { VaultFileCodec.decode(ByteArray(64) { 1 }, vault, "x") } // bad magic
        val badVersion = VaultFileCodec.encode(vault, "a.md", byteArrayOf(1), fixedNonce()).copyOf().also { it[7] = 9 }
        assertFailsWith<VaultFormatException> { VaultFileCodec.decode(badVersion, vault, vault.cnameOf("a.md")) }
    }

    @Test
    fun encode_rejects_oversized_and_invalid_paths() = runTest {
        val vault = fixedVault()
        assertFailsWith<VaultFormatException> { VaultFileCodec.encode(vault, "../etc/passwd", byteArrayOf()) }
        assertFailsWith<VaultFormatException> {
            VaultFileCodec.encode(vault, "/".repeat(1) + "x".repeat(70_000) + ".md", byteArrayOf())
        }
    }

    @Test
    fun file_names_are_flat_and_filesystem_safe() = runTest {
        val vault = fixedVault()
        val name = vault.fileNameOf("очень глубоко/вложенная/папка/заметка.md")
        assertTrue(name.endsWith(".gwv"))
        assertTrue(name.dropLast(4).all { it in 'a'..'z' || it in '2'..'7' })
        assertEquals(36, name.length) // 32 cname chars + ".gwv"
    }

    // Pinned once on the JVM; re-verified on every platform so all targets agree byte-for-byte.
    @Test
    fun known_answer_vector() = runTest {
        val vault = fixedVault()
        val encoded = VaultFileCodec.encode(vault, "Идеи/черновик.md", "привет".encodeToByteArray(), fixedNonce())
        assertEquals(KAT_FILE_HEX, Hex.encode(encoded))
        val decoded = VaultFileCodec.decode(encoded, vault, vault.cnameOf("Идеи/черновик.md"))
        assertEquals("привет", decoded.content.decodeToString())
    }

    private companion object {
        // Pinned output of encode(fixedVault(), "Идеи/черновик.md", "привет", fixedNonce()) -
        // guards the container format (magic | version | key id | nonce | AEAD) against drift.
        const val KAT_FILE_HEX = "4757564c54310a010708090a0b0c0d0e" +
            "0205080b0e1114171a1d202326292c2f3235383b3e414447" +
            "7514b7702156fd8da08b83cbf0158ba8d678b65878dfab28cc42db7bf42b053cfdfb1f9cd1361799c4879f78545091b2580cf7caafc2ca67f479"
    }
}
