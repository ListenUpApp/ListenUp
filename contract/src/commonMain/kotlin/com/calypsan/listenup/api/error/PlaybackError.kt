package com.calypsan.listenup.api.error

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Domain errors for the audio-playback surface.
 *
 * These are typed values that cross the client error bus uniformly.
 * UI consumes [message] directly; logs consume [debugInfo] and [correlationId].
 *
 * All subtypes after [CouldNotStart] are client-raised: no server code produces them.
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

        override fun withCorrelationId(id: String?): AppError = copy(correlationId = id)
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

        override fun withCorrelationId(id: String?): AppError = copy(correlationId = id)
    }

    /**
     * Playback preparation failed for a reason other than the book being held: no server
     * configured, the book or its audio files missing locally and unfetchable, or the streaming
     * prepare failing. Raised by the client's playback choke point
     * (`com.calypsan.listenup.client.playback.PlaybackPreparer.prepare`); [debugInfo] carries the
     * specific cause.
     *
     * Not retryable in the middleware sense: callers decide (Now Playing offers a retry and words
     * an offline failure differently).
     */
    @Serializable
    @SerialName("PlaybackError.CouldNotStart")
    data class CouldNotStart(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : PlaybackError {
        override val message: String = "This book couldn't be started."
        override val code: String = "PLAYBACK_COULD_NOT_START"
        override val isRetryable: Boolean = false

        override fun withCorrelationId(id: String?): AppError = copy(correlationId = id)
    }

    /**
     * The connection to the server dropped while a streaming book played, and the engine's own recovery
     * could not bring it back: Android's `PlaybackErrorHandler` after its retry budget, a web hls.js or
     * `MEDIA_ERR_NETWORK` failure, or an AVFoundation item failure in a network domain. Client-raised.
     *
     * Retryable: re-preparing the same book can be re-fired blindly.
     */
    @Serializable
    @SerialName("PlaybackError.ConnectionLost")
    data class ConnectionLost(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : PlaybackError {
        override val message: String = "The connection to your server dropped."
        override val code: String = "PLAYBACK_CONNECTION_LOST"
        override val isRetryable: Boolean = true

        override fun withCorrelationId(id: String?): AppError = copy(correlationId = id)
    }

    /**
     * The server cannot be reached and this book has nothing on disk to play: raised by a prepare, or
     * proactively when `BookAvailability.canPlay` is false. On the web it is the only "can't play" cause,
     * because a browser keeps no downloads. Client-raised.
     */
    @Serializable
    @SerialName("PlaybackError.ServerUnreachable")
    data class ServerUnreachable(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : PlaybackError {
        override val message: String = "Can't reach your server."
        override val code: String = "PLAYBACK_SERVER_UNREACHABLE"
        override val isRetryable: Boolean = true

        override fun withCorrelationId(id: String?): AppError = copy(correlationId = id)
    }

    /**
     * The device is offline and the loaded book has no downloaded audio. Raised proactively, before Play,
     * on platforms that keep downloads. Client-raised; waits for the network, so not retryable by middleware.
     */
    @Serializable
    @SerialName("PlaybackError.OfflineNotDownloaded")
    data class OfflineNotDownloaded(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : PlaybackError {
        override val message: String = "You're offline, and this book isn't downloaded."
        override val code: String = "PLAYBACK_OFFLINE_NOT_DOWNLOADED"
        override val isRetryable: Boolean = false

        override fun withCorrelationId(id: String?): AppError = copy(correlationId = id)
    }

    /**
     * The device's decoder cannot play the book's audio: a Media3 decoder or format error, a web
     * `MEDIA_ERR_SRC_NOT_SUPPORTED`/`MEDIA_ERR_DECODE`, or an AVFoundation format error. A user Retry
     * re-prepares, which lets the server negotiate a transcode. Client-raised.
     */
    @Serializable
    @SerialName("PlaybackError.UnsupportedFormat")
    data class UnsupportedFormat(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : PlaybackError {
        override val message: String = "This device can't play this book's audio format."
        override val code: String = "PLAYBACK_UNSUPPORTED_FORMAT"
        override val isRetryable: Boolean = false

        override fun withCorrelationId(id: String?): AppError = copy(correlationId = id)
    }

    /**
     * The browser refused `play()` with `NotAllowedError`: it needs a user gesture before it plays sound.
     * Web only. A tap on Play is that gesture. Client-raised.
     */
    @Serializable
    @SerialName("PlaybackError.AutoplayBlocked")
    data class AutoplayBlocked(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : PlaybackError {
        override val message: String = "Tap Play to start. Your browser needs a tap before it plays sound."
        override val code: String = "PLAYBACK_AUTOPLAY_BLOCKED"
        override val isRetryable: Boolean = false

        override fun withCorrelationId(id: String?): AppError = copy(correlationId = id)
    }

    /**
     * The loaded book left the reader's library (a revoke or a delete) and is not a downloaded, in-progress
     * copy, so playback stopped. Raised by the shared liveness teardown. Client-raised.
     */
    @Serializable
    @SerialName("PlaybackError.NoLongerAvailable")
    data class NoLongerAvailable(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : PlaybackError {
        override val message: String = "This book is no longer in your library."
        override val code: String = "PLAYBACK_NO_LONGER_AVAILABLE"
        override val isRetryable: Boolean = false

        override fun withCorrelationId(id: String?): AppError = copy(correlationId = id)
    }
}
