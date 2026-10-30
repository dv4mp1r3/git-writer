@file:OptIn(kotlin.ExperimentalUnsignedTypes::class)

package dev.jotalac.feature.vault.crypto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The keystore is a small file stored next to the notes (`.gw-vault/keystore`, kept out of git by
 * the notebook's `.gitignore`): it holds the master key wrapped by the passphrase-derived KEK.
 * The passphrase (and the file itself) never travels - other devices learn the key through the
 * recovery key string or an exported keystore file, and only the encrypted notes are synced.
 */
@Serializable
data class VaultKeystore(
    @SerialName("version") val version: Int,
    @SerialName("key_id") val keyIdHex: String,
    @SerialName("kdf") val kdf: KdfParams,
    @SerialName("wrapped") val wrapped: WrappedKey,
) {
    companion object {
        const val FORMAT_VERSION = 1
    }
}

@Serializable
data class KdfParams(
    @SerialName("algo") val algo: String,
    @SerialName("opslimit") val opsLimit: Int,
    @SerialName("memlimit") val memLimit: Int,
    @SerialName("salt") val saltBase64: String,
)

@Serializable
data class WrappedKey(
    @SerialName("nonce") val nonceBase64: String,
    @SerialName("ciphertext") val ciphertextBase64: String,
)

/** A vault opened with a passphrase, a recovery key, or a key cached on the device. */
class OpenedVault(
    /** The keystore stored on this device; null when unlocked without one (no local keystore). */
    val keystoreJson: String?,
    val keystore: VaultKeystore?,
    val vault: UnlockedVault,
) {
    constructor(vault: UnlockedVault) : this(keystoreJson = null, keystore = null, vault = vault)

    /** Re-wraps the same master key under a new passphrase; the data itself is not re-encrypted. */
    internal suspend fun rewrap(passphrase: String): String =
        KeystoreCodec.wrapAndSerialize(vault.keys.masterKey, passphrase, vault.keyId)
}

object KeystoreCodec {

    // Domain-separation tag for the keystore wrapping (AAD), 8 chars like the KDF contexts.
    private const val WRAP_AAD = "gwkeyst1"

    private val json = Json { ignoreUnknownKeys = true }

    /** Creates a fresh vault with a random master key wrapped by [passphrase]. */
    suspend fun createVault(passphrase: String): OpenedVault {
        val keyId = VaultCrypto.randomBytes(UnlockedVault.KEY_ID_BYTES).toByteArray()
        val keys = VaultKeys.generate()
        val keystoreJson = wrapAndSerialize(keys.masterKey, passphrase, keyId)
        val keystore = parse(keystoreJson)
        return OpenedVault(keystoreJson, keystore, UnlockedVault(keyId, keys))
    }

    /**
     * Unwraps the master key with [passphrase].
     * @throws VaultWrongPassphraseException when the passphrase does not open the keystore
     */
    suspend fun unlock(keystoreJson: String, passphrase: String): OpenedVault {
        val keystore = parse(keystoreJson)
        val masterKey = unwrap(keystore, passphrase)
        val keys = VaultKeys.fromMasterKey(masterKey)
        return OpenedVault(keystoreJson, keystore, UnlockedVault(Hex.decode(keystore.keyIdHex), keys))
    }

    /**
     * Opens a vault whose passphrase was lost: the [recoveryKey] string decodes directly to the
     * master key, no KDF involved. A vault cannot be opened by feeding the recovery key to
     * [unlock] - it is not a passphrase. A GW2 key names its vault (embedded key id) and must
     * match the keystore; a legacy GW1 key carries no key id and is accepted as-is.
     * @throws VaultFormatException when the keystore or the recovery key string is malformed
     */
    suspend fun unlockWithRecoveryKey(keystoreJson: String, recoveryKey: String): OpenedVault {
        val keystore = parse(keystoreJson)
        if (keystore.version != VaultKeystore.FORMAT_VERSION) {
            throw VaultFormatException("Unsupported keystore version: ${keystore.version}")
        }
        val decoded = RecoveryKeyCodec.decode(recoveryKey)
        if (decoded.keyIdHex != null && decoded.keyIdHex != keystore.keyIdHex) {
            throw VaultFormatException("The recovery key does not open this vault")
        }
        return OpenedVault(keystoreJson, keystore, UnlockedVault(Hex.decode(keystore.keyIdHex), VaultKeys.fromMasterKey(decoded.masterKey)))
    }

    /** @return the new keystore JSON; master key and all encrypted data stay the same. */
    suspend fun changePassphrase(keystoreJson: String, currentPassphrase: String, newPassphrase: String): String {
        val opened = unlock(keystoreJson, currentPassphrase)
        return opened.rewrap(newPassphrase)
    }

    internal suspend fun wrapAndSerialize(
        masterKey: ByteArray,
        passphrase: String,
        keyId: ByteArray,
        salt: ByteArray,
        nonce: ByteArray,
    ): String {
        val kek = deriveKek(passphrase, salt)
        val ciphertext = VaultCrypto.aeadEncrypt(kek, nonce, masterKey, aadFor(keyId))
        val keystore = VaultKeystore(
            version = VaultKeystore.FORMAT_VERSION,
            keyIdHex = Hex.encode(keyId),
            kdf = KdfParams(
                algo = "argon2id",
                opsLimit = VaultKdfParams.OPSLIMIT,
                memLimit = VaultKdfParams.MEMLIMIT,
                saltBase64 = encodeBase64(salt),
            ),
            wrapped = WrappedKey(nonceBase64 = encodeBase64(nonce), ciphertextBase64 = encodeBase64(ciphertext)),
        )
        return json.encodeToString(keystore)
    }

    internal suspend fun wrapAndSerialize(masterKey: ByteArray, passphrase: String, keyId: ByteArray): String =
        wrapAndSerialize(
            masterKey = masterKey,
            passphrase = passphrase,
            keyId = keyId,
            salt = VaultCrypto.randomBytes(VaultKdfParams.SALT_BYTES).toByteArray(),
            nonce = VaultCrypto.generateNonce().toByteArray(),
        )

    private suspend fun unwrap(keystore: VaultKeystore, passphrase: String): ByteArray {
        if (keystore.version != VaultKeystore.FORMAT_VERSION) {
            throw VaultFormatException("Unsupported keystore version: ${keystore.version}")
        }
        if (keystore.kdf.algo != "argon2id") {
            throw VaultFormatException("Unsupported KDF: ${keystore.kdf.algo}")
        }
        val keyId = Hex.decode(keystore.keyIdHex)
        val kek = deriveKek(passphrase, decodeBase64(keystore.kdf.saltBase64), keystore.kdf.opsLimit, keystore.kdf.memLimit)
        return try {
            VaultCrypto.aeadDecrypt(
                key = kek,
                nonce = decodeBase64(keystore.wrapped.nonceBase64),
                ciphertext = decodeBase64(keystore.wrapped.ciphertextBase64),
                additionalData = aadFor(keyId),
            )
        } catch (e: VaultTamperedDataException) {
            throw VaultWrongPassphraseException(e)
        }.also {
            if (it.size != VaultKeys.MASTER_KEY_BYTES) throw VaultFormatException("Corrupt master key in keystore")
        }
    }

    private fun parse(keystoreJson: String): VaultKeystore = try {
        json.decodeFromString<VaultKeystore>(keystoreJson)
    } catch (e: Exception) {
        throw VaultFormatException("Corrupt keystore", e)
    }

    /** Parses a keystore JSON without unlocking it (e.g. to read the key id). */
    fun parseKeystore(keystoreJson: String): VaultKeystore = parse(keystoreJson)

    private fun aadFor(keyId: ByteArray): ByteArray = WRAP_AAD.encodeToByteArray() + keyId

    // kotlin.io.encoding.Base64 is stable on this Kotlin version but padded variants only; the
    // helpers centralize the choice so the format stays pinned.
    private fun encodeBase64(bytes: ByteArray): String = kotlin.io.encoding.Base64.Default.encode(bytes)
    private fun decodeBase64(text: String): ByteArray = kotlin.io.encoding.Base64.Default.decode(text)
}
