package com.calypsan.listenup.api.error

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Domain errors for the audio-playback surface.
 *
 * These are typed values that cross the client error bus uniformly.
 * UI consumes [message] directly; logs consume [debugInfo] and [correlationId].
 *
 * **Note on naming:** the legacy [com.calypsan.listenup.client.playback.PlaybackManager.PlaybackErrorUiState]
 * data class is the UI-state model (transient, shown in a snackbar) and is intentionally separate
 * from this typed-error hierarchy. The two have different lifetimes — UI state is per-error
 * presentation, [PlaybackError] is the contract value.
 */
@Serializable
sealed interface PlaybackError : AppError {
    /**
     * Media3 detected the player stuck in `STATE_BUFFERING` past the watchdog threshold
     * (`STUCK_BUFFERING_TIMEOUT_MS`, which the client sets well below Media3's ten-minute default).
     *
     * Surfaces via Media3's `PlaybackException.ERROR_CODE_TIMEOUT`.
     * Recovery: the error handler re-prepares the player automatically, within a bounded retry
     * budget; the accompanying snackbar lets the user retry manually once that budget is spent.
     */
    @Serializable
    @SerialName("PlaybackError.Stalled")
    data class Stalled(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : PlaybackError {
        override val message: String = "Playback stalled. Tap to retry."
        override val code: String = "PLAYBACK_STALLED"
        override val isRetryable: Boolean = true
    }

    /**
     * The platform refused to let playback start because the app was in the background.
     *
     * Android 17's background audio hardening denies an audio-focus request outright when the app
     * has neither a visible activity nor a running foreground service, and denies the foreground
     * service start that would have made it eligible — a deadlock a background surface cannot
     * break out of on its own. Nothing throws; without this value the refusal is silent.
     *
     * Not retryable in the middleware sense: re-firing the same request from the same background
     * state is refused identically. It clears when the listener brings the app to the foreground,
     * which is what the message asks for.
     */
    @Serializable
    @SerialName("PlaybackError.BlockedInBackground")
    data class BlockedInBackground(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : PlaybackError {
        override val message: String = "Playback can't start in the background. Open ListenUp to resume."
        override val code: String = "PLAYBACK_BLOCKED_IN_BACKGROUND"
        override val isRetryable: Boolean = false
    }
}
