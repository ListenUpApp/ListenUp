package com.calypsan.listenup.core

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.TimeSource
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.withContext
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.posix.memcpy
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlock
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * iOS implementation of SecureStorage using Keychain Services.
 *
 * Features:
 * - Hardware-backed encryption via Secure Enclave (when available)
 * - Items accessible after first device unlock (kSecAttrAccessibleAfterFirstUnlock)
 * - Thread-safe operations via IODispatcher
 * - Proper memory management with memScoped
 */
private val logger = KotlinLogging.logger {}

/**
 * NSUserDefaults flag marking that the Keychain has been initialized for the current install.
 * NSUserDefaults is wiped on app uninstall (the Keychain is not), so the flag's absence means
 * "this is a fresh install" — see [AppleSecureStorage.clearKeychainOnFreshInstall].
 */
private const val KEYCHAIN_INSTALL_FLAG_KEY = "com.calypsan.listenup.keychain_initialized"

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class AppleSecureStorage : SecureStorage {
    private val serviceName = "com.calypsan.listenup"

    init {
        clearKeychainOnFreshInstall()
    }

    /**
     * Make an app uninstall actually reset the app.
     *
     * iOS Keychain items survive an uninstall/reinstall by design — only a device wipe clears them.
     * Without this, a reinstall would resurrect the previous install's session and pending-registration
     * state (stranding the user on a stale Awaiting screen, etc.). NSUserDefaults, by contrast, IS
     * cleared on uninstall — so the absence of [KEYCHAIN_INSTALL_FLAG_KEY] identifies a fresh install:
     * wipe any leftover credentials for this service, then set the flag so subsequent launches no-op.
     *
     * Synchronous (init-time, before anything reads auth state) and best-effort — `errSecItemNotFound`
     * on a genuine first launch is expected and fine.
     */
    private fun clearKeychainOnFreshInstall() {
        val defaults = NSUserDefaults.standardUserDefaults
        if (defaults.boolForKey(KEYCHAIN_INSTALL_FLAG_KEY)) return

        val query =
            CFDictionaryCreateMutable(
                null,
                2,
                kCFTypeDictionaryKeyCallBacks.ptr,
                kCFTypeDictionaryValueCallBacks.ptr,
            )!!
        CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
        CFDictionarySetValue(query, kSecAttrService, CFBridgingRetain(serviceName))
        SecItemDelete(query)

        defaults.setBool(true, KEYCHAIN_INSTALL_FLAG_KEY)
        logger.info { "Fresh install detected; cleared stale Keychain credentials so the uninstall resets the app" }
    }

    override suspend fun save(
        key: String,
        value: String,
    ) = withContext(IODispatcher) {
        logger.debug { "Keychain save started: $key" }
        val startMark = TimeSource.Monotonic.markNow()

        val bytes = value.encodeToByteArray()
        val data = bytes.toNSData()

        // Delete existing item first
        val deleteQuery =
            CFDictionaryCreateMutable(
                null,
                4,
                kCFTypeDictionaryKeyCallBacks.ptr,
                kCFTypeDictionaryValueCallBacks.ptr,
            )!!
        CFDictionarySetValue(deleteQuery, kSecClass, kSecClassGenericPassword)
        CFDictionarySetValue(deleteQuery, kSecAttrService, CFBridgingRetain(serviceName))
        CFDictionarySetValue(deleteQuery, kSecAttrAccount, CFBridgingRetain(key))

        SecItemDelete(deleteQuery)

        // Add new item
        val addQuery =
            CFDictionaryCreateMutable(
                null,
                6,
                kCFTypeDictionaryKeyCallBacks.ptr,
                kCFTypeDictionaryValueCallBacks.ptr,
            )!!
        CFDictionarySetValue(addQuery, kSecClass, kSecClassGenericPassword)
        CFDictionarySetValue(addQuery, kSecAttrService, CFBridgingRetain(serviceName))
        CFDictionarySetValue(addQuery, kSecAttrAccount, CFBridgingRetain(key))
        CFDictionarySetValue(addQuery, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlock)
        CFDictionarySetValue(addQuery, kSecValueData, CFBridgingRetain(data))

        val status = SecItemAdd(addQuery, null)
        val elapsed = startMark.elapsedNow()
        logger.debug { "Keychain save completed: $key ($elapsed, status=$status)" }

        if (status != errSecSuccess) {
            throw SecurityException("Failed to save to keychain: $status")
        }
    }

    override suspend fun read(key: String): String? =
        withContext(IODispatcher) {
            val query =
                CFDictionaryCreateMutable(
                    null,
                    5,
                    kCFTypeDictionaryKeyCallBacks.ptr,
                    kCFTypeDictionaryValueCallBacks.ptr,
                )!!
            CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionarySetValue(query, kSecAttrService, CFBridgingRetain(serviceName))
            CFDictionarySetValue(query, kSecAttrAccount, CFBridgingRetain(key))
            CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
            CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)

            memScoped {
                val result = alloc<CFTypeRefVar>()
                val status = SecItemCopyMatching(query, result.ptr)

                if (status == errSecSuccess) {
                    // Bridge the CFTypeRef to NSData using toll-free bridging
                    // result.value is CFTypeRef? which bridges to NSObject?
                    result.value?.let { cfData ->
                        val nativePtr = cfData.rawValue
                        val data = kotlinx.cinterop.interpretObjCPointerOrNull<NSData>(nativePtr)
                        return@withContext data?.let {
                            NSString.create(it, NSUTF8StringEncoding)?.toString()
                        }
                    }
                    return@withContext null
                }

                if (status == errSecItemNotFound) {
                    return@withContext null
                }

                throw SecurityException("Failed to read from keychain: $status")
            }
        }

    override suspend fun delete(key: String) =
        withContext(IODispatcher) {
            val query =
                CFDictionaryCreateMutable(
                    null,
                    3,
                    kCFTypeDictionaryKeyCallBacks.ptr,
                    kCFTypeDictionaryValueCallBacks.ptr,
                )!!
            CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionarySetValue(query, kSecAttrService, CFBridgingRetain(serviceName))
            CFDictionarySetValue(query, kSecAttrAccount, CFBridgingRetain(key))

            val status = SecItemDelete(query)
            if (status != errSecSuccess && status != errSecItemNotFound) {
                throw SecurityException("Failed to delete from keychain: $status")
            }
        }

    override suspend fun clear() =
        withContext(IODispatcher) {
            val query =
                CFDictionaryCreateMutable(
                    null,
                    2,
                    kCFTypeDictionaryKeyCallBacks.ptr,
                    kCFTypeDictionaryValueCallBacks.ptr,
                )!!
            CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionarySetValue(query, kSecAttrService, CFBridgingRetain(serviceName))

            val status = SecItemDelete(query)
            if (status != errSecSuccess && status != errSecItemNotFound) {
                throw SecurityException("Failed to clear keychain: $status")
            }
        }
}

/**
 * Custom exception for iOS Keychain errors.
 */
class SecurityException(
    message: String,
) : Exception(message)

@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    }
}
