package com.calypsan.listenup.server.push

/**
 * Resolved push-relay endpoint. Defaults to the ListenUp project relay; forks
 * and self-run relays override via LISTENUP_PUSH_RELAY_URL / push.relayUrl.
 * URL presence is necessary but not sufficient — the admin setting
 * pushNotificationsEnabled is the runtime on/off switch (checked at use sites).
 */
data class PushConfig(
    val relayUrl: String?,
) {
    val configured: Boolean get() = !relayUrl.isNullOrBlank()

    companion object {
        const val DEFAULT_RELAY_URL = "https://push.listenup.audio"
    }
}
