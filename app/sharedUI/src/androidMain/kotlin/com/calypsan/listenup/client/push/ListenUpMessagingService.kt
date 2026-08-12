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
        if (shouldSuppressForeground(ProcessLifecycleOwner.get().lifecycle.currentState)) return

        val payload = decodePushPayload(message.data) // null → generic notification, never a crash

        runBlocking { renderer.render(payload) }
    }

    override fun onNewToken(token: String) {
        runBlocking { registrar.onTokenRotated(token) }
    }
}

/**
 * Decodes the FCM data payload into a typed [PushPayload], or `null` when the `payload` key is
 * absent, its discriminator is unknown (a future push kind an older client doesn't recognize
 * yet), or the JSON is malformed — the caller falls back to a generic notification, never a crash.
 */
internal fun decodePushPayload(data: Map<String, String>): PushPayload? =
    data["payload"]?.let { raw ->
        runCatching { contractJson.decodeFromString(PushPayload.serializer(), raw) }.getOrNull()
    }

/** True when the app is foregrounded enough that the in-app SSE-fed surface already covers this event. */
internal fun shouldSuppressForeground(state: Lifecycle.State): Boolean = state.isAtLeast(Lifecycle.State.STARTED)
