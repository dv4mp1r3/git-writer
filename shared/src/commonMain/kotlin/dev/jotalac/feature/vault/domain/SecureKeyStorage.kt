package dev.jotalac.feature.vault.domain

/**
 * Device-local cache of unlocked vault master keys, keyed by the vault key id. The master key
 * must not be stored in the clear; each platform protects it with its own credential store
 * (Android Keystore, iOS Keychain, DPAPI on Windows, a 0600 file on desktop Linux/macOS).
 */
interface SecureKeyCache {
    suspend fun store(keyIdHex: String, masterKey: ByteArray)
    suspend fun get(keyIdHex: String): ByteArray?
    suspend fun remove(keyIdHex: String)
}

expect class SecureKeyStorage : SecureKeyCache {
    override suspend fun store(keyIdHex: String, masterKey: ByteArray)
    override suspend fun get(keyIdHex: String): ByteArray?
    override suspend fun remove(keyIdHex: String)
}
