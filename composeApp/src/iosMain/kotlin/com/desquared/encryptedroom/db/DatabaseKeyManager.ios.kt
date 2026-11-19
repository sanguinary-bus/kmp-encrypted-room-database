package com.desquared.encryptedroom.db

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.dataWithBytes
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

private const val KEYCHAIN_SERVICE = "com.desquared.encryptedroom.database"
private const val KEYCHAIN_ACCOUNT = "passphrase"
private const val PASSPHRASE_SIZE = 32

// shortcut: the Keychain CF objects are never CFReleased (one passphrase per install), release them
// if this ever runs more than once per launch.
@OptIn(ExperimentalForeignApi::class)
internal actual fun getOrCreateDatabasePassphrase(): ByteArray {
    readPassphrase()?.let { return it }

    val passphrase = ByteArray(PASSPHRASE_SIZE)
    val status = passphrase.usePinned {
        SecRandomCopyBytes(null, PASSPHRASE_SIZE.toULong(), it.addressOf(0))
    }
    check(status == errSecSuccess) { "SecRandomCopyBytes failed with status $status" }
    storePassphrase(passphrase)
    return passphrase
}

@OptIn(ExperimentalForeignApi::class)
private fun readPassphrase(): ByteArray? = memScoped {
    val query = keychainQuery()
    CFDictionaryAddValue(query, kSecReturnData, kCFBooleanTrue)
    CFDictionaryAddValue(query, kSecMatchLimit, kSecMatchLimitOne)

    val result = alloc<CFTypeRefVar>()
    if (SecItemCopyMatching(query, result.ptr) != errSecSuccess) {
        return@memScoped null
    }
    val data = CFBridgingRelease(result.value) as? NSData ?: return@memScoped null
    val length = data.length.toInt()
    ByteArray(length).also { bytes ->
        bytes.usePinned { data.getBytes(it.addressOf(0), length.toULong()) }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun storePassphrase(passphrase: ByteArray) {
    SecItemDelete(keychainQuery())

    val data = passphrase.usePinned {
        NSData.dataWithBytes(it.addressOf(0), passphrase.size.toULong())
    }
    val query = keychainQuery()
    CFDictionaryAddValue(query, kSecValueData, CFBridgingRetain(data))
    CFDictionaryAddValue(
        query,
        kSecAttrAccessible,
        kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
    )
    val status = SecItemAdd(query, null)
    check(status == errSecSuccess) { "SecItemAdd failed with status $status" }
}

@OptIn(ExperimentalForeignApi::class)
private fun keychainQuery(): CFMutableDictionaryRef? {
    val query = CFDictionaryCreateMutable(null, 0L, null, null)
    CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
    CFDictionaryAddValue(query, kSecAttrService, cfString(KEYCHAIN_SERVICE))
    CFDictionaryAddValue(query, kSecAttrAccount, cfString(KEYCHAIN_ACCOUNT))
    return query
}

@OptIn(ExperimentalForeignApi::class)
private fun cfString(value: String): CFStringRef? =
    CFStringCreateWithCString(null, value, kCFStringEncodingUTF8)
