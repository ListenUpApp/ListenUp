package com.calypsan.listenup.client.data.push

/**
 * Platform hook returning this device's push token, or `null` when push isn't
 * available here (no Play services, desktop). Bound only where a real provider
 * exists (the Android platform module); consumers inject it nullable — the
 * absence of a binding on a platform (desktop) is the intended "push doesn't
 * exist here" signal, not a wiring bug.
 */
interface PushTokenProvider {
    /**
     * Returns the current platform push token, or `null` if unavailable
     * (missing Play services, registration in progress, or any lookup failure).
     */
    suspend fun currentToken(): String?
}
