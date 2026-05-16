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
     * (default 10 minutes in Media3 1.9.0+).
     *
     * Surfaces via [androidx.media3.common.PlaybackException.ERROR_CODE_TIMEOUT].
     * Recovery: re-prepare the player. First retry is automatic; a second stall within
     * 30 seconds surfaces as a permanent error.
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
}
