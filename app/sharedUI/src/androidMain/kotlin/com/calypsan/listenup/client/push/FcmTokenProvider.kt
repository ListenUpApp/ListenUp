package com.calypsan.listenup.client.push

import com.calypsan.listenup.client.data.push.PushTokenProvider
import com.google.android.gms.tasks.Task
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Android [PushTokenProvider] backed by Firebase Cloud Messaging.
 *
 * The "token" is the device's Firebase installation ID: FCM registers it with `register()` and
 * reports it through `ListenUpMessagingService.onRegistered`, which [handoff] routes back here.
 * The `Task` is awaited with [suspendCancellableCoroutine] rather than pulling in
 * `kotlinx-coroutines-play-services` for a single call site.
 */
class FcmTokenProvider(
    private val handoff: FcmRegistrationHandoff,
    private val requestRegistration: suspend () -> Unit = {
        FirebaseMessaging.getInstance().register().awaitCompletion()
    },
) : PushTokenProvider {
    override suspend fun currentToken(): String? =
        try {
            handoff.register(requestRegistration)
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
            null // No Play services / Firebase not initialized: SSE-only, by design.
        }
}

/** Suspends until this `Task` finishes, throwing its failure. A cancelled `Task` counts as a failure. */
private suspend fun Task<*>.awaitCompletion() {
    suspendCancellableCoroutine { cont ->
        addOnCompleteListener { task ->
            cont.resumeWith(
                if (task.isSuccessful) {
                    Result.success(Unit)
                } else {
                    Result.failure(task.exception ?: IllegalStateException("FCM registration was cancelled"))
                },
            )
        }
    }
}
