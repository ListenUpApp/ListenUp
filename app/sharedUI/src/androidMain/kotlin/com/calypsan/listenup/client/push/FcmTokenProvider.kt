package com.calypsan.listenup.client.push

import com.calypsan.listenup.client.data.push.PushTokenProvider
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Android [PushTokenProvider] backed by Firebase Cloud Messaging. Wraps the
 * SDK's `Task`-based API in [suspendCancellableCoroutine] rather than pulling
 * in `kotlinx-coroutines-play-services` for a single call site.
 */
class FcmTokenProvider : PushTokenProvider {
    // getToken() is deprecated for register()/onRegistered; moving to that callback is a push-flow redesign.
    @Suppress("Deprecation")
    override suspend fun currentToken(): String? =
        try {
            suspendCancellableCoroutine { cont ->
                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    cont.resumeWith(Result.success(if (task.isSuccessful) task.result else null))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
            null // No Play services / Firebase not initialized: SSE-only, by design.
        }
}
