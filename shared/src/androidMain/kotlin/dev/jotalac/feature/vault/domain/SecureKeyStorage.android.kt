package dev.jotalac.feature.vault.domain

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Master keys wrapped by a non-exportable AES-GCM key held in the Android Keystore; the wrapped
 * blobs live in DataStore. Removing the app (or the keystore entry) makes the blobs unreadable.
 */
actual class SecureKeyStorage(private val dataStore: DataStore<Preferences>) : SecureKeyCache {

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "git_writer_vault"
        private const val IV_BYTES = 12
        private fun keyPref(keyIdHex: String) = stringPreferencesKey("vault_master_key_$keyIdHex")
    }

    actual override suspend fun store(keyIdHex: String, masterKey: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        require(iv.size == IV_BYTES) { "Unexpected GCM IV length: ${iv.size}" }
        val wrapped = cipher.doFinal(masterKey)
        val blob = Base64.getEncoder().encodeToString(iv + wrapped)
        dataStore.edit { it[keyPref(keyIdHex)] = blob }
    }

    actual override suspend fun get(keyIdHex: String): ByteArray? {
        val blob = dataStore.data.first()[keyPref(keyIdHex)] ?: return null
        val bytes = try {
            Base64.getDecoder().decode(blob)
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (bytes.size <= IV_BYTES) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, bytes, 0, IV_BYTES))
        return cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES)
    }

    actual override suspend fun remove(keyIdHex: String) {
        dataStore.edit { it.remove(keyPref(keyIdHex)) }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            generator.init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generator.generateKey()
        }
        return keyStore.getKey(KEY_ALIAS, null) as SecretKey
    }
}
