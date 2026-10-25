package dev.jotalac.feature.vault

import dev.jotalac.feature.vault.crypto.KeystoreCodec
import dev.jotalac.feature.vault.crypto.RecoveryKeyCodec
import dev.jotalac.feature.vault.crypto.VaultCrypto
import dev.jotalac.feature.vault.crypto.VaultFileCodec
import dev.jotalac.feature.vault.crypto.VaultFormatException
import dev.jotalac.feature.vault.crypto.VaultKeys
import dev.jotalac.feature.vault.crypto.VaultWrongPassphraseException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KeystoreCodecTest {

    @Test
    fun create_then_unlock_roundtrip() = runTest {
        val opened = KeystoreCodec.createVault("пароль синхронизации")
        val reopened = KeystoreCodec.unlock(opened.keystoreJson, "пароль синхронизации")
        assertContentEquals(opened.vault.keyId, reopened.vault.keyId)
        assertContentEquals(opened.vault.keys.contentKey, reopened.vault.keys.contentKey)
        assertContentEquals(opened.vault.keys.masterKey, reopened.vault.keys.masterKey)
    }

    @Test
    fun wrong_passphrase_is_rejected_cleanly() = runTest {
        val opened = KeystoreCodec.createVault("right-password")
        assertFailsWith<VaultWrongPassphraseException> { KeystoreCodec.unlock(opened.keystoreJson, "wrong-password") }
        // empty and unicode wrong guesses fail the same way, not with a raw crypto error
        assertFailsWith<VaultWrongPassphraseException> { KeystoreCodec.unlock(opened.keystoreJson, "") }
        assertFailsWith<VaultWrongPassphraseException> { KeystoreCodec.unlock(opened.keystoreJson, "правильный пароль") }
    }

    @Test
    fun unlock_accepts_recovery_key_instead_of_passphrase() = runTest {
        val opened = KeystoreCodec.createVault("pass-123")
        val recovery = RecoveryKeyCodec.encode(opened.vault.keys.masterKey)
        // the recovery key is the master key itself, not a passphrase - unlock must reject it
        assertFailsWith<VaultWrongPassphraseException> { KeystoreCodec.unlock(opened.keystoreJson, recovery) }
        // its own path decodes straight to the master key
        val reopened = KeystoreCodec.unlockWithRecoveryKey(opened.keystoreJson, recovery)
        assertContentEquals(opened.vault.keys.masterKey, reopened.vault.keys.masterKey)
        assertContentEquals(opened.vault.keyId, reopened.vault.keyId)
    }

    @Test
    fun change_passphrase_keeps_the_same_master_key() = runTest {
        val opened = KeystoreCodec.createVault("old-pass")
        // encrypt a file before the change
        val file = VaultFileCodec.encode(opened.vault, "note.md", "данные".encodeToByteArray())
        val cname = opened.vault.cnameOf("note.md")

        val newJson = KeystoreCodec.changePassphrase(opened.keystoreJson, "old-pass", "new-pass")

        assertFailsWith<VaultWrongPassphraseException> { KeystoreCodec.unlock(newJson, "old-pass") }
        val reopened = KeystoreCodec.unlock(newJson, "new-pass")
        assertContentEquals(opened.vault.keys.masterKey, reopened.vault.keys.masterKey)
        assertContentEquals(opened.vault.keyId, reopened.vault.keyId)
        // old encrypted data still opens with the reopened vault
        val decoded = VaultFileCodec.decode(file, reopened.vault, cname)
        assertEquals("данные", decoded.content.decodeToString())
    }

    @Test
    fun keystore_json_schema_is_pinned() = runTest {
        val opened = KeystoreCodec.createVault("p")
        val root = Json.parseToJsonElement(opened.keystoreJson).jsonObject
        assertEquals("1", root["version"]!!.jsonPrimitive.content)
        assertEquals(dev.jotalac.feature.vault.crypto.Hex.encode(opened.vault.keyId), root["key_id"]!!.jsonPrimitive.content)
        val kdf = root["kdf"]!!.jsonObject
        assertEquals("argon2id", kdf["algo"]!!.jsonPrimitive.content)
        assertTrue("salt" in kdf)
        val wrapped = root["wrapped"]!!.jsonObject
        assertTrue("nonce" in wrapped)
        assertTrue("ciphertext" in wrapped)
    }

    @Test
    fun corrupt_keystore_is_a_format_error() = runTest {
        assertFailsWith<VaultFormatException> { KeystoreCodec.unlock("{ not json", "x") }
    }

    // Pinned keystore JSON for masterKey (i*7+1), keyId (i+7), salt (i*11+3), wrap nonce (i*5+1),
    // passphrase "пароль-тест" - guards the keystore format and the Argon2id wrapping against drift.
    @Test
    fun keystore_known_answer_vector() = runTest {
        VaultCrypto.init()
        val masterKey = ByteArray(32) { (it * 7 + 1).toByte() }
        val keyId = ByteArray(8) { (it + 7).toByte() }
        val salt = ByteArray(16) { (it * 11 + 3).toByte() }
        val nonce = ByteArray(24) { (it * 5 + 1).toByte() }
        val json = KeystoreCodec.wrapAndSerialize(masterKey, "пароль-тест", keyId, salt, nonce)
        assertEquals(KAT_KEYSTORE_JSON, json)
        val reopened = KeystoreCodec.unlock(KAT_KEYSTORE_JSON, "пароль-тест")
        assertContentEquals(masterKey, reopened.vault.keys.masterKey)
        assertContentEquals(keyId, reopened.vault.keyId)
    }

    @Test
    fun recovery_key_string_roundtrip() = runTest {
        VaultCrypto.init()
        val masterKey = ByteArray(32) { (it * 5 + 3).toByte() }
        val text = RecoveryKeyCodec.encode(masterKey)
        assertTrue(text.startsWith("GW1-"))
        assertEquals(12, text.split('-').size) // prefix + 10 full groups of 5 + tail of 2
        assertContentEquals(masterKey, RecoveryKeyCodec.decode(text))
        assertContentEquals(masterKey, RecoveryKeyCodec.decode(text.lowercase()))
        assertContentEquals(masterKey, RecoveryKeyCodec.decode("  ${text.replace('-', ' ')}  "))
    }

    @Test
    fun recovery_key_rejects_garbage() = runTest {
        VaultCrypto.init()
        assertFailsWith<VaultFormatException> {
            RecoveryKeyCodec.decode("XX1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        }
        assertFailsWith<VaultFormatException> { RecoveryKeyCodec.decode("GW1-short") }
    }

    private companion object {
        const val KAT_KEYSTORE_JSON = """{"version":1,"key_id":"0708090a0b0c0d0e","kdf":{"algo":"argon2id","opslimit":3,"memlimit":268435456,"salt":"Aw4ZJC86RVBbZnF8h5KdqA=="},"wrapped":{"nonce":"AQYLEBUaHyQpLjM4PUJHTFFWW2Blam90","ciphertext":"kfHVfg4lYmyumdiKACN9kuTphY13p8O6q+fNalmXIv0mSxEcy897boBCjYC8A41V"}}"""
    }
}
