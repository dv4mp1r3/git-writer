package dev.jotalac.feature.vault

import dev.jotalac.feature.vault.crypto.Base32
import dev.jotalac.feature.vault.crypto.Hex
import dev.jotalac.feature.vault.crypto.VaultFormatException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EncodingsTest {

    // RFC 4648 section 10 vectors, unpadded (our alphabet is lowercase)
    @Test
    fun base32_rfc4648_vectors() {
        assertEquals("", Base32.encode(ByteArray(0)))
        assertEquals("my", Base32.encode(byteArrayOf(0x66)))
        assertEquals("mzxw6ytboi", Base32.encode("foobar".encodeToByteArray()))
    }

    @Test
    fun base32_roundtrip() {
        val bytes = ByteArray(64) { (it * 31 + 7).toByte() }
        assertContentEquals(bytes, Base32.decode(Base32.encode(bytes)))
        // exactly 20 bytes - the cname body length
        val cnameBody = ByteArray(20) { (it * 13).toByte() }
        assertEquals(32, Base32.encode(cnameBody).length)
        assertContentEquals(cnameBody, Base32.decode(Base32.encode(cnameBody)))
    }

    @Test
    fun base32_decode_is_case_insensitive_and_tolerates_padding() {
        assertContentEquals(byteArrayOf(0x66), Base32.decode("MY"))
        assertContentEquals(byteArrayOf(0x66), Base32.decode("my======"))
    }

    @Test
    fun base32_decode_rejects_garbage() {
        assertFailsWith<VaultFormatException> { Base32.decode("abc1") } // '1' is not in the alphabet
        assertFailsWith<VaultFormatException> { Base32.decode("m") } // a lone char cannot fill a byte
    }

    @Test
    fun base32_uppercase_option() {
        assertEquals("MY", Base32.encode(byteArrayOf(0x66), uppercase = true))
    }

    @Test
    fun hex_roundtrip() {
        val bytes = byteArrayOf(0x00, 0x0f, (0xF0).toByte(), (0xFF).toByte(), 0x7A)
        assertEquals("000ff0ff7a", Hex.encode(bytes))
        assertContentEquals(bytes, Hex.decode("000ff0ff7a"))
        assertContentEquals(bytes, Hex.decode("000FF0FF7A"))
        assertFailsWith<VaultFormatException> { Hex.decode("abc") }
        assertFailsWith<VaultFormatException> { Hex.decode("zz") }
    }
}
