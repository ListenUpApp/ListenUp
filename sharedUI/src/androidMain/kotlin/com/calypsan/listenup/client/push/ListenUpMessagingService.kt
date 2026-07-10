package com.calypsan.listenup.client.push

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.push.PushPayload
import com.calypsan.listenup.client.data.push.PushRegistrar
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Receives FCM token refreshes and incoming data messages for push notifications.
 *
 * [onMessageReceived] and [onNewToken] both bridge into suspend functions with [runBlocking].
 * This is acceptable here — unlike the ban on `runBlocking` in the rest of the production
 * codebase — because both callbacks already run on FCM's own background executor (never the
 * main thread) and are budgeted by the platform for roughly 10 seconds of synchronous work; there
 * is no UI thread to block and no caller expecting a faster return. This is the same "bridge a
 * synchronous platform callback into a suspend call" idiom as
 * [com.calypsan.listenup.client.playback.AudioTokenAuthenticator].
 */
class ListenUpMessagingService :
    FirebaseMessagingService(),
    KoinComponent {
    private val renderer: PushNotificationRenderer by inject()
    private val registrar: PushRegistrar by inject()

    override fun onMessageReceived(message: RemoteMessage) {
        // Foreground suppression: the in-app SSE-fed surface already shows live events, so a
        // foregrounded app skips the local notification entirely.
        val foreground =
            ProcessLifecycleOwner
                .get()
                .lifecycle.currentState
                .isAtLeast(Lifecycle.State.STARTED)
        if (foreground) return

        val payload =
            message.data["payload"]?.let { raw ->
                runCatching { contractJson.decodeFromString(PushPayload.serializer(), raw) }.getOrNull()
            } // null (absent OR unknown discriminator) → generic notification, never a crash

        runBlocking { renderer.render(payload) }
    }

    override fun onNewToken(token: String) {
        runBlocking { registrar.onTokenRotated(token) }
    }
}
