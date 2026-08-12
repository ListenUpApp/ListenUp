package com.calypsan.listenup.server.push

/**
 * Resolved push-relay endpoint and sender credential. [relayUrl] defaults to the
 * ListenUp project relay; forks and self-run relays override via
 * LISTENUP_PUSH_RELAY_URL / push.relayUrl. URL presence is necessary but not
 * sufficient — the admin setting pushNotificationsEnabled is the runtime on/off
 * switch (checked at use sites). [relayToken] is the shared secret the relay's
 * `/v1/send` validates (see the relay's `PROTOCOL.md` § Sender credential) —
 * currently optional (phase 1: the relay still serves callers that omit it), set
 * via LISTENUP_PUSH_RELAY_TOKEN / push.relayToken. Never logged.
 *
 * [enabled] is a deploy-time master switch (LISTENUP_PUSH_ENABLED / push.enabled, default ON).
 * When false, push is OFF regardless of the runtime admin toggle (a hard operator override): no
 * device tokens are registered, [ServerInfo.pushEnabled] is false so clients never send a token,
 * and the relay is never contacted. The runtime admin setting pushNotificationsEnabled gates on
 * top of this — both must be on for push to run.
 */
data class PushConfig(
    val relayUrl: String?,
    val relayToken: String? = null,
    val enabled: Boolean = true,
) {
    /** Push is usable: enabled by the deploy-time [enabled] switch AND a relay URL is set. */
    val configured: Boolean get() = enabled && !relayUrl.isNullOrBlank()

    companion object {
        const val DEFAULT_RELAY_URL = "https://push.listenup.audio"
    }
}
