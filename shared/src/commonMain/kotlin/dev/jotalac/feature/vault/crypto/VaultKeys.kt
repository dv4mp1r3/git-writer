@file:OptIn(kotlin.ExperimentalUnsignedTypes::class)

package dev.jotalac.feature.vault.crypto

import com.ionspin.kotlin.crypto.generichash.GenericHash
import com.ionspin.kotlin.crypto.kdf.Kdf
import com.ionspin.kotlin.crypto.pwhash.PasswordHash
import com.ionspin.kotlin.crypto.pwhash.crypto_pwhash_ALG_DEFAULT

/** Default Argon2id parameters; they are versioned inside the keystore so they can be raised later. */
object VaultKdfParams {
    const val OPSLIMIT = 3
    const val MEMLIMIT = 268_435_456 // 256 MiB (libsodium "moderate")
    const val SALT_BYTES = 16
}

/** Domain separation contexts for crypto_kdf_derive_from_key (must be exactly 8 chars). */
private const val CONTEXT_CONTENT = "gwcontt1"
private const val CONTEXT_NAME = "gwnamet1"

/**
 * The per-vault key set. The master key never leaves the vault except wrapped by the keystore;
 * only the domain-separated subkeys ever touch data.
 */
class VaultKeys private constructor(
    val masterKey: ByteArray,
    val contentKey: ByteArray,
    val nameKey: ByteArray,
) {
    companion object {
        const val MASTER_KEY_BYTES = 32
        const val CNAME_BYTES = 20 // 160-bit encrypted file name body -> 32 base32 chars

        suspend fun fromMasterKey(masterKey: ByteArray): VaultKeys {
            VaultCrypto.init()
            require(masterKey.size == MASTER_KEY_BYTES) { "Master key must be $MASTER_KEY_BYTES bytes" }
            return VaultKeys(
                masterKey = masterKey,
                contentKey = Kdf.deriveFromKey(0u, VaultCrypto.KEY_BYTES, CONTEXT_CONTENT, masterKey.toUByteArray()).toByteArray(),
                nameKey = Kdf.deriveFromKey(1u, VaultCrypto.KEY_BYTES, CONTEXT_NAME, masterKey.toUByteArray()).toByteArray(),
            )
        }

        suspend fun generate(): VaultKeys = fromMasterKey(VaultCrypto.generateKey().toByteArray())
    }

    /**
     * Deterministic encrypted file name for a note path: keyed BLAKE2b of the canonical path.
     * The same note always maps to the same name on every device, which is what lets git
     * merge conflicts work exactly as they do in plaintext.
     */
    fun cnameOf(rawRelPath: String): String {
        val canonical = canonicalRelPath(rawRelPath)
        val hash = GenericHash.genericHash(canonical.encodeToByteArray().toUByteArray(), CNAME_BYTES, nameKey.toUByteArray())
        return Base32.encode(hash.toByteArray())
    }
}

/**
 * Passphrase -> key-encryption-key (Argon2id). Used only to wrap/unwrap the master key in the
 * keystore; parameters are stored in the keystore so they can change without breaking old vaults.
 */
suspend fun deriveKek(
    passphrase: String,
    salt: ByteArray,
    opsLimit: Int = VaultKdfParams.OPSLIMIT,
    memLimit: Int = VaultKdfParams.MEMLIMIT,
): ByteArray {
    VaultCrypto.init()
    require(salt.size == VaultKdfParams.SALT_BYTES) { "Argon2id salt must be ${VaultKdfParams.SALT_BYTES} bytes" }
    return PasswordHash.pwhash(
        outputLength = VaultCrypto.KEY_BYTES,
        password = passphrase,
        salt = salt.toUByteArray(),
        opsLimit = opsLimit.toUInt().toULong(),
        memLimit = memLimit,
        algorithm = crypto_pwhash_ALG_DEFAULT,
    ).toByteArray()
}
