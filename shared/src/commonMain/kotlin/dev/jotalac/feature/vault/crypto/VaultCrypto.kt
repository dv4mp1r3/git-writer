@file:OptIn(kotlin.ExperimentalUnsignedTypes::class)

package dev.jotalac.feature.vault.crypto

import com.ionspin.kotlin.crypto.LibsodiumInitializer
import com.ionspin.kotlin.crypto.aead.AeadCorrupedOrTamperedDataException
import com.ionspin.kotlin.crypto.aead.AuthenticatedEncryptionWithAssociatedData
import com.ionspin.kotlin.crypto.util.LibsodiumRandom
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Thin facade over libsodium (via the ionspin multiplatform bindings) used by the whole vault.
 * Every function makes sure the native library is initialized first; the init itself is
 * idempotent and cheap once done.
 */
object VaultCrypto {

    /** AES-grade key length used for the master key and all derived subkeys. */
    const val KEY_BYTES = 32

    /** XChaCha20-Poly1305 nonce length (192 bits - random nonces need no counter management). */
    const val NONCE_BYTES = 24

    private val initMutex = Mutex()
    private var initialized = false

    suspend fun init() {
        if (initialized) return
        initMutex.withLock {
            if (!initialized) {
                LibsodiumInitializer.initialize()
                initialized = true
            }
        }
    }

    suspend fun randomBytes(count: Int): UByteArray {
        init()
        return LibsodiumRandom.buf(count)
    }

    suspend fun generateKey(): UByteArray = randomBytes(KEY_BYTES)

    suspend fun generateNonce(): UByteArray = randomBytes(NONCE_BYTES)

    /** Encrypts [plaintext] with XChaCha20-Poly1305; returns ciphertext with the 16-byte tag appended. */
    suspend fun aeadEncrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        additionalData: ByteArray,
    ): ByteArray {
        init()
        return AuthenticatedEncryptionWithAssociatedData
            .xChaCha20Poly1305IetfEncrypt(plaintext.toUByteArray(), additionalData.toUByteArray(), nonce.toUByteArray(), key.toUByteArray())
            .toByteArray()
    }

    /**
     * Decrypts [ciphertext] produced by [aeadEncrypt].
     * @throws VaultTamperedDataException on any authentication failure.
     */
    suspend fun aeadDecrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        additionalData: ByteArray,
    ): ByteArray {
        init()
        return try {
            AuthenticatedEncryptionWithAssociatedData
                .xChaCha20Poly1305IetfDecrypt(ciphertext.toUByteArray(), additionalData.toUByteArray(), nonce.toUByteArray(), key.toUByteArray())
                .toByteArray()
        } catch (e: AeadCorrupedOrTamperedDataException) {
            throw VaultTamperedDataException(cause = e)
        }
    }
}
