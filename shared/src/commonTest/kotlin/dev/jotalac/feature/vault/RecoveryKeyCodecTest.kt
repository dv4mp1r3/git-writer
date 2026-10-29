package dev.jotalac.feature.vault

import dev.jotalac.feature.vault.crypto.Base32
import dev.jotalac.feature.vault.crypto.Hex
import dev.jotalac.feature.vault.crypto.RecoveryKeyCodec
import dev.jotalac.feature.vault.crypto.VaultCrypto
import dev.jotalac.feature.vault.crypto.VaultFormatException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RecoveryKeyCodecTest {

    @Test
    fun gw2_roundtrip_carries_key_id_and_master_key() {
        val keyId = ByteArray(8) { (it * 3 + 1).toByte() }
        val masterKey = ByteArray(32) { (it * 5 + 3).toByte() }
        val text = RecoveryKeyCodec.encode(keyId, masterKey)
        assertTrue(text.startsWith("GW2-"))
        assertEquals(14, text.split('-').size) // prefix + 12 full groups of 5 + tail of 4
        val decoded = RecoveryKeyCodec.decode(text)
        assertEquals(Hex.encode(keyId), decoded.keyIdHex)
        assertContentEquals(masterKey, decoded.masterKey)
    }

    @Test
    fun input_is_case_and_separator_tolerant() {
        val keyId = ByteArray(8) { it.toByte() }
        val masterKey = ByteArray(32) { (it + 1).toByte() }
        val text = RecoveryKeyCodec.encode(keyId, masterKey)
        val lowercased = RecoveryKeyCodec.decode(text.lowercase())
        assertEquals(Hex.encode(keyId), lowercased.keyIdHex)
        assertContentEquals(masterKey, lowercased.masterKey)
        val spaced = RecoveryKeyCodec.decode("  ${text.replace('-', ' ')}  ")
        assertEquals(Hex.encode(keyId), spaced.keyIdHex)
        assertContentEquals(masterKey, spaced.masterKey)
    }

    @Test
    fun legacy_gw1_key_decodes_without_key_id() {
        // a key saved by an older app version: GW1 + the raw master key, no key id inside
        val masterKey = ByteArray(32) { (it * 5 + 3).toByte() }
        val text = Base32.encode(masterKey, uppercase = true).chunked(5).joinToString("-", prefix = "GW1-")
        val decoded = RecoveryKeyCodec.decode(text)
        assertEquals(null, decoded.keyIdHex)
        assertContentEquals(masterKey, decoded.masterKey)
    }

    @Test
    fun rejects_garbage_and_wrong_lengths() {
        assertFailsWith<VaultFormatException> {
            RecoveryKeyCodec.decode("XX1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        }
        assertFailsWith<VaultFormatException> { RecoveryKeyCodec.decode("GW1-short") }
        assertFailsWith<VaultFormatException> { RecoveryKeyCodec.decode("GW2-short") }
        assertFailsWith<VaultFormatException> { RecoveryKeyCodec.decode("") }
        assertFailsWith<VaultFormatException> {
            // a GW1-length payload (32 bytes) is not a valid GW2 payload (40 bytes)
            RecoveryKeyCodec.decode("GW2-" + Base32.encode(ByteArray(32) { it.toByte() }, uppercase = true))
        }
    }

    // Pinned GW2 vector: keyId (i+7), masterKey (i*7+1) - guards the string format against drift.
    @Test
    fun gw2_known_answer_vector() = kotlinx.coroutines.test.runTest {
        VaultCrypto.init()
        val keyId = ByteArray(8) { (it + 7).toByte() }
        val masterKey = ByteArray(32) { (it * 7 + 1).toByte() }
        assertEquals(KAT_GW2, RecoveryKeyCodec.encode(keyId, masterKey))
        val decoded = RecoveryKeyCodec.decode(KAT_GW2)
        assertEquals("0708090a0b0c0d0e", decoded.keyIdHex)
        assertContentEquals(masterKey, decoded.masterKey)
    }

    private companion object {
        const val KAT_GW2 =
            "GW2-A4EAS-CQLBQ-GQ4AI-IB4LB-2JBLG-I4UAR-2OKVO-GG2TR-PB7YN-DMUTO-RKTMF-XX3C4-ZU62"
    }
}
