package dev.jotalac.testing.fakes

import dev.jotalac.feature.vault.domain.SecureKeyCache

/** A [SecureKeyCache] keeping every key in a map - stand-in for the platform secure storage. */
class InMemorySecureKeyCache : SecureKeyCache {

    val stored = mutableMapOf<String, ByteArray>()

    override suspend fun store(keyIdHex: String, masterKey: ByteArray) {
        stored[keyIdHex] = masterKey
    }

    override suspend fun get(keyIdHex: String): ByteArray? = stored[keyIdHex]

    override suspend fun remove(keyIdHex: String) {
        stored.remove(keyIdHex)
    }
}
