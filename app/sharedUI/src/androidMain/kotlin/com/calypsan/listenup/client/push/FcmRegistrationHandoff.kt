package com.calypsan.listenup.client.push

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Joins FCM's two halves of registration back into one answer.
 *
 * `FirebaseMessaging.register()` completes without a value; the installation ID the server must
 * target arrives separately, as `FirebaseMessagingService.onRegistered`, on FCM's worker thread.
 * [register] asks for a registration and waits for that callback; [offer] is how the service
 * hands each callback over. A callback nobody is waiting for is a registration FCM started on
 * its own — first install, or the installation ID changing — and the service treats it as a
 * rotation. A caller that gives up waiting leaves its callback to that same path, so a late
 * installation ID still reaches the server.
 */
class FcmRegistrationHandoff(
    private val callbackTimeout: Duration = DEFAULT_CALLBACK_TIMEOUT,
) {
    private val waiters = mutableListOf<CompletableDeferred<String>>()

    /**
     * Runs [requestRegistration] and returns the installation ID its `onRegistered` delivers, or
     * `null` if the callback doesn't arrive within the timeout. Exceptions from
     * [requestRegistration] propagate.
     */
    suspend fun register(requestRegistration: suspend () -> Unit): String? {
        val waiter = CompletableDeferred<String>()
        synchronized(waiters) { waiters += waiter }
        try {
            requestRegistration()
            return withTimeoutOrNull(callbackTimeout) { waiter.await() }
        } finally {
            synchronized(waiters) { waiters -= waiter }
        }
    }

    /**
     * Hands an `onRegistered` installation ID to every caller waiting in [register]. Returns
     * `true` if one claimed it — that caller registers it with the server — or `false` when
     * nobody was waiting, meaning the caller should treat it as a rotation.
     */
    fun offer(installationId: String): Boolean {
        val claimants =
            synchronized(waiters) {
                waiters.toList().also { waiters.clear() }
            }
        claimants.forEach { it.complete(installationId) }
        return claimants.isNotEmpty()
    }

    private companion object {
        /** `register()` has already succeeded by the time we wait, so the callback is moments away. */
        val DEFAULT_CALLBACK_TIMEOUT = 10.seconds
    }
}
