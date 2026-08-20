package com.calypsan.listenup.server.push

/**
 * Resolved push-relay endpoint. Defaults to the ListenUp project relay; forks
 * and self-run relays override via LISTENUP_PUSH_RELAY_URL / push.relayUrl.
 * URL presence is necessary but not sufficient — the admin setting
 * pushNotificationsEnabled is the runtime on/off switch (checked at use sites).
 */
data class PushConfig(
    val relayUrl: String?,
    /**
     * Shared secret presented to the relay as `Authorization: Bearer <token>`.
     *
     * The relay's rollout is two-phase (PROTOCOL.md "Sender credential"): today an ABSENT
     * credential is accepted and only a WRONG one is rejected, so this being null still works.
     * Phase 2 makes it mandatory, at which point a server that never learned to send it starts
     * getting 401s on every push — silently, because push is best-effort and swallows failures.
     * Sending it now means that switch is a non-event rather than an outage nobody notices.
     *
     * Null for a relay that has not been provisioned with one (a fork running its own).
     */
    val senderToken: String? = null,
) {
    val configured: Boolean get() = !relayUrl.isNullOrBlank()

    companion object {
        const val DEFAULT_RELAY_URL = "https://push.listenup.audio"
    }
}
