package com.calypsan.listenup.core

/**
 * Platform-agnostic interface for secure credential storage.
 *
 * Implementations use platform-specific secure storage mechanisms:
 * - Android: EncryptedSharedPreferences with AES256_GCM via Android Keystore
 * - iOS: Keychain Services with hardware-backed encryption
 *
 * All operations are suspend functions to avoid blocking the main thread.
 */
interface SecureStorage {
    /**
     * Save a key-value pair to secure storage.
     * Overwrites existing value if key already exists.
     */
    suspend fun save(
        key: String,
        value: String,
    )

    /**
     * Read a value from secure storage.
     * @return The stored value, or null if key doesn't exist
     */
    suspend fun read(key: String): String?

    /**
     * Read a credential, telling "nothing stored" apart from "stored but unreadable right now".
     *
     * [read] folds a transient platform fault into `null` for the many callers that can only
     * shrug at one. A credential cannot afford that: a refresh token that reads as absent ends the
     * session, while one that merely could not be read this instant should be tried again.
     *
     * @return the stored value, or null only when the key genuinely holds nothing (or holds bytes
     *   that are permanently undecryptable, which is the same thing to a caller).
     * @throws SecureStorageUnavailableException when a value is stored but could not be read now.
     */
    suspend fun readCredential(key: String): String? = read(key)

    /**
     * Delete a specific key from secure storage.
     * Safe to call even if key doesn't exist.
     */
    suspend fun delete(key: String)

    /**
     * Clear all data from secure storage.
     * Use with caution - this removes all stored credentials.
     */
    suspend fun clear()
}
