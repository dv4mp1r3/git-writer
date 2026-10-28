package dev.jotalac.feature.vault

import com.ionspin.kotlin.crypto.LibsodiumInitializer
import com.ionspin.kotlin.crypto.aead.AeadCorrupedOrTamperedDataException
import com.ionspin.kotlin.crypto.aead.AuthenticatedEncryptionWithAssociatedData
import com.ionspin.kotlin.crypto.generichash.GenericHash
import com.ionspin.kotlin.crypto.pwhash.PasswordHash
import com.ionspin.kotlin.crypto.pwhash.crypto_pwhash_ALG_DEFAULT
import com.ionspin.kotlin.crypto.util.LibsodiumRandom
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 0 spike: verifies the libsodium bindings resolve and the native library loads on every
 * target this source set compiles for (JVM, Android host, iOS simulator in CI).
 */
@OptIn(ExperimentalUnsignedTypes::class)
class LibsodiumSmokeTest {

    private suspend fun initSodium() {
        if (!LibsodiumInitializer.isInitialized()) {
            LibsodiumInitializer.initializeWithCallback { }
        }
    }

    private fun bytes(s: String): UByteArray = s.encodeToByteArray().toUByteArray()

    @Test
    fun xchacha20poly1305_roundtrip() = runTest {
        initSodium()
        val key = LibsodiumRandom.buf(32)
        val nonce = LibsodiumRandom.buf(24)
        val aad = bytes("gw-test-aad")
        val message = bytes("привет, заметка!")

        val ciphertext = AuthenticatedEncryptionWithAssociatedData.xChaCha20Poly1305IetfEncrypt(message, aad, nonce, key)
        val decrypted = AuthenticatedEncryptionWithAssociatedData.xChaCha20Poly1305IetfDecrypt(ciphertext, aad, nonce, key)

        assertContentEquals(message, decrypted)

        // flipping one ciphertext bit must fail authentication
        val corrupted = ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toUByte() }
        assertFailsWith<AeadCorrupedOrTamperedDataException> {
            AuthenticatedEncryptionWithAssociatedData.xChaCha20Poly1305IetfDecrypt(corrupted, aad, nonce, key)
        }
    }

    @Test
    fun aad_is_bound() = runTest {
        initSodium()
        val key = LibsodiumRandom.buf(32)
        val nonce = LibsodiumRandom.buf(24)
        val message = bytes("secret")
        val ciphertext = AuthenticatedEncryptionWithAssociatedData.xChaCha20Poly1305IetfEncrypt(message, bytes("aad-1"), nonce, key)
        assertFailsWith<AeadCorrupedOrTamperedDataException> {
            AuthenticatedEncryptionWithAssociatedData.xChaCha20Poly1305IetfDecrypt(ciphertext, bytes("aad-2"), nonce, key)
        }
    }

    @Test
    fun argon2id_is_deterministic() = runTest {
        initSodium()
        val salt = LibsodiumRandom.buf(16)
        val password = bytes("пароль синхронизации")
        val hash1 = hashArgon2id(password, salt)
        val hash2 = hashArgon2id(password, salt)
        assertEquals(32, hash1.size)
        assertContentEquals(hash1, hash2)
        val otherSaltHash = hashArgon2id(password, LibsodiumRandom.buf(16))
        assertFalse(hash1.contentEquals(otherSaltHash))
    }

    @Test
    fun keyed_blake2b_is_deterministic() = runTest {
        initSodium()
        val nameKey = LibsodiumRandom.buf(32)
        val path = bytes("notes/ideas.md")
        val h1 = GenericHash.genericHash(path, 20, nameKey)
        val h2 = GenericHash.genericHash(path, 20, nameKey)
        assertEquals(20, h1.size)
        assertContentEquals(h1, h2)
        val otherKeyHash = GenericHash.genericHash(path, 20, LibsodiumRandom.buf(32))
        assertFalse(h1.contentEquals(otherKeyHash))
    }

    @Test
    fun random_bytes_differ() = runTest {
        initSodium()
        val a = LibsodiumRandom.buf(24)
        val b = LibsodiumRandom.buf(24)
        assertEquals(24, a.size)
        assertFalse(a.contentEquals(b))
        assertTrue(a.any { it != 0.toUByte() })
    }

    // opslimit/memlimit are smoke-level; the real parameters will live in the vault keystore
    private fun hashArgon2id(password: UByteArray, salt: UByteArray): UByteArray =
        PasswordHash.pwhash(
            outputLength = 32,
            password = password.toByteArray().decodeToString(),
            salt = salt,
            opsLimit = 1u,
            memLimit = 8_388_608, // 8 MiB, enough to exercise Argon2id
            algorithm = crypto_pwhash_ALG_DEFAULT,
        )
}
