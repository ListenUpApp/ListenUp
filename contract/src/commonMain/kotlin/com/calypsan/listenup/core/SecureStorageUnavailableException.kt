package com.calypsan.listenup.core

/**
 * A value IS stored under [key], but secure storage could not read it right now — the Android
 * Keystore pruned or briefly lost its key, the iOS Keychain is locked. Distinct from "nothing
 * stored", which [SecureStorage.readCredential] reports as `null`.
 *
 * The distinction exists because the two demand opposite responses. An absent refresh token means
 * the session is over; an unreadable one means try again in a moment. Folding the second into the
 * first turned a storage blip into a permanent sign-out.
 */
class SecureStorageUnavailableException(
    val key: String,
    cause: Throwable? = null,
) : Exception("Secure storage could not read '$key' right now", cause)
