package dev.jotalac.feature.vault.domain

import com.sun.jna.platform.win32.Crypt32Util
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64

/**
 * Windows: the master key is wrapped with the user's DPAPI credentials (CryptProtectData) and
 * kept in the app data folder. macOS/Linux have no cross-user credential store available to the
 * JVM without extra native glue, so the key is kept in a 0600 file in the app data folder; the
 * UI warns about that on Linux (macOS gets Keychain storage as a follow-up).
 */
actual class SecureKeyStorage : SecureKeyCache {

    private val keysDir: File by lazy {
        val base = System.getenv("APPDATA")?.takeIf { isWindows() }
            ?: File(System.getProperty("user.home"), ".git-writer").absolutePath
        File(base, "vault-keys").apply { mkdirs() }
    }

    private fun keyFile(keyIdHex: String): File = File(keysDir, "${keyIdHex}.key")

    actual override suspend fun store(keyIdHex: String, masterKey: ByteArray): Unit = withContext(Dispatchers.IO) {
        val file = keyFile(keyIdHex)
        if (isWindows()) {
            file.writeBytes(Base64.getEncoder().encode(Crypt32Util.cryptProtectData(masterKey)))
        } else {
            file.writeBytes(Base64.getEncoder().encode(masterKey))
            restrictPermissions(file)
        }
    }

    actual override suspend fun get(keyIdHex: String): ByteArray? = withContext(Dispatchers.IO) {
        val file = keyFile(keyIdHex)
        if (!file.isFile) return@withContext null
        val stored = try {
            Base64.getDecoder().decode(file.readBytes())
        } catch (e: IllegalArgumentException) {
            return@withContext null
        }
        try {
            if (isWindows()) Crypt32Util.cryptUnprotectData(stored) else stored
        } catch (e: Exception) {
            null // cannot unprotect on this machine/user anymore - treat as absent
        }
    }

    actual override suspend fun remove(keyIdHex: String): Unit = withContext(Dispatchers.IO) {
        keyFile(keyIdHex).delete()
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("windows")

    private fun restrictPermissions(file: File) {
        try {
            Files.setPosixFilePermissions(
                Path.of(file.absolutePath),
                PosixFilePermissions.fromString("rw-------")
            )
        } catch (e: Exception) {
            // not a POSIX file system - nothing to restrict
        }
    }
}
