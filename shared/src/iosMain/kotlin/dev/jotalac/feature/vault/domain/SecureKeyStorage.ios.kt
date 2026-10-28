package dev.jotalac.feature.vault.domain

import kotlinx.cinterop.CFTypeRefVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.Foundation.CFDataRef
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * Master keys go straight into the iOS Keychain as generic passwords, reachable after the first
 * device unlock only and never synced to other devices.
 */
@OptIn(ExperimentalForeignApi::class)
actual class SecureKeyStorage : SecureKeyCache {

    private companion object {
        const val SERVICE = "dev.jotalac.git-writer.vault"
    }

    actual override suspend fun store(keyIdHex: String, masterKey: ByteArray) {
        remove(keyIdHex) // the Keychain has no upsert
        memScoped {
            val account = cfString(keyIdHex)
            val service = cfString(SERVICE)
            val data = cfData(masterKey)
            val query = CFDictionaryCreateMutable(null, 0, null, null)
            CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionaryAddValue(query, kSecAttrService, service)
            CFDictionaryAddValue(query, kSecAttrAccount, account)
            CFDictionaryAddValue(query, kSecValueData, data)
            CFDictionaryAddValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
            val status = SecItemAdd(query, null)
            CFRelease(query)
            CFRelease(data)
            CFRelease(service)
            CFRelease(account)
            check(status == errSecSuccess) { "Keychain SecItemAdd failed with status $status" }
        }
    }

    actual override suspend fun get(keyIdHex: String): ByteArray? {
        memScoped {
            val account = cfString(keyIdHex)
            val service = cfString(SERVICE)
            val query = CFDictionaryCreateMutable(null, 0, null, null)
            CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionaryAddValue(query, kSecAttrService, service)
            CFDictionaryAddValue(query, kSecAttrAccount, account)
            CFDictionaryAddValue(query, kSecReturnData, kCFBooleanTrue)
            CFDictionaryAddValue(query, kSecMatchLimit, kSecMatchLimitOne)

            val result = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query, result.ptr)
            CFRelease(query)
            CFRelease(service)
            CFRelease(account)

            if (status == errSecItemNotFound) return null
            if (status != errSecSuccess) error("Keychain SecItemCopyMatching failed with status $status")

            val cfData = result.value?.reinterpret<CFData>() ?: return null
            val length = CFDataGetLength(cfData).toInt()
            val bytesPtr = CFDataGetBytePtr(cfData) ?: return null
            return ByteArray(length) { bytesPtr[it] }
        }
    }

    actual override suspend fun remove(keyIdHex: String) {
        memScoped {
            val account = cfString(keyIdHex)
            val service = cfString(SERVICE)
            val query = CFDictionaryCreateMutable(null, 0, null, null)
            CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionaryAddValue(query, kSecAttrService, service)
            CFDictionaryAddValue(query, kSecAttrAccount, account)
            SecItemDelete(query)
            CFRelease(query)
            CFRelease(service)
            CFRelease(account)
        }
    }

    private fun cfString(text: String): CFStringRef = CFStringCreateWithCString(null, text, kCFStringEncodingUTF8)

    private fun cfData(bytes: ByteArray): CFDataRef? = bytes.usePinned { pinned ->
        CFDataCreate(null, pinned.addressOf(0), bytes.size.toLong())
    }
}
