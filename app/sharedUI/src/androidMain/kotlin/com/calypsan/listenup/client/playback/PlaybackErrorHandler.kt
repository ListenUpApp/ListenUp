package com.calypsan.listenup.client.playback

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import com.calypsan.listenup.core.BookId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay

private val logger = KotlinLogging.logger {}

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404

/**
 * Handles playback errors with the principle: "Position is sacred."
 *
 * Error handling strategy:
 * 1. ALWAYS save position before showing error (never lose progress)
 * 2. ALWAYS show clear, actionable error message (never silent failures)
 * 3. AUTO-RETRY network errors (transient failures are common)
 * 4. FAIL FAST on auth/404/codec errors (don't waste time retrying the impossible)
 * 5. LOG everything (debugging > user messaging)
 */
class PlaybackErrorHandler(
    private val progressTracker: ProgressTracker,
    private val tokenProvider: AndroidAudioTokenProvider,
) {
    /**
     * Classifies errors into actionable categories.
     */
    sealed class ClassifiedError {
        // Retryable - ExoPlayer handles internally, we just wait
        data class Network(
            val message: String,
        ) : ClassifiedError()

        // Retryable once - refresh token, retry request
        data class AuthExpired(
            val message: String,
        ) : ClassifiedError()

        // Not retryable - user action required
        data class NotFound(
            val message: String,
        ) : ClassifiedError()

        data class Codec(
            val message: String,
        ) : ClassifiedError()

        // Stuck player - Media3 1.9.0 detects when playback is stuck
        // Triggers after 10 min buffering, 10s ready with no progress, etc.
        data class Stuck(
            val message: String,
        ) : ClassifiedError()

        data class Unknown(
            val cause: Throwable,
        ) : ClassifiedError()
    }

    /**
     * Maps ExoPlayer exceptions to our error types.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun classify(error: PlaybackException): ClassifiedError =
        when (error.errorCode) {
            // Network errors - ExoPlayer will retry, we just observe
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            -> {
                ClassifiedError.Network("Network connection lost")
            }

            // HTTP errors - check status code
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
                val cause = error.cause
                val statusCode = (cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode

                when (statusCode) {
                    HTTP_UNAUTHORIZED -> ClassifiedError.AuthExpired("Session expired")
                    HTTP_FORBIDDEN -> ClassifiedError.AuthExpired("Access denied")
                    HTTP_NOT_FOUND -> ClassifiedError.NotFound("Audio file not found")
                    in 500..599 -> ClassifiedError.Network("Server error, retrying...")
                    else -> ClassifiedError.Unknown(error)
                }
            }

            // Decoder errors - file is broken or unsupported
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            -> {
                ClassifiedError.Codec("Cannot play this audio format")
            }

            // Stuck player detection (Media3 1.9.0)
            // Fires after 10 min buffering, 10s ready with no progress, etc.
            PlaybackException.ERROR_CODE_TIMEOUT -> {
                ClassifiedError.Stuck("Playback appears to be stuck")
            }

            else -> {
                ClassifiedError.Unknown(error)
            }
        }

    /**
     * Handle error based on classification.
     * Returns true if playback should continue (error was handled).
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    suspend fun handle(
        error: ClassifiedError,
        player: ExoPlayer,
        currentBookId: BookId?,
        bookPositionMs: Long,
        onShowError: (String) -> Unit,
    ): Boolean {
        // ALWAYS save position first - position is sacred.
        // The caller passes a BOOK-relative position (PlaybackService.getBookRelativePosition()).
        // Reading player.currentPosition here would save a FILE-relative offset — for a
        // multi-file book in a late file that persists a ~9h regression as the newest position.
        currentBookId?.let { bookId ->
            progressTracker.savePositionNow(bookId, bookPositionMs)
            logger.debug { "Position saved before error handling: $bookPositionMs" }
        }

        return when (error) {
            is ClassifiedError.Network -> {
                // ExoPlayer handles retry internally
                // Just log and let it buffer
                logger.info { "Network error, ExoPlayer buffering: ${error.message}" }
                true // Continue, ExoPlayer is handling it
            }

            is ClassifiedError.AuthExpired -> {
                logger.warn { "Auth expired during playback" }

                // Try token refresh
                tokenProvider.onUnauthorized()
                delay(1000) // Give refresh a moment

                val newToken = tokenProvider.getToken()
                if (newToken != null) {
                    // Token refreshed, retry
                    logger.info { "Token refreshed, retrying playback" }
                    player.prepare()
                    player.play()
                    true
                } else {
                    // Refresh failed - user needs to re-auth
                    onShowError("Session expired. Please sign in again.")
                    player.pause()
                    false
                }
            }

            is ClassifiedError.NotFound -> {
                logger.error { "Audio file not found: ${error.message}" }
                onShowError("This audio file is no longer available.")
                player.pause()
                false
            }

            is ClassifiedError.Codec -> {
                logger.error { "Codec error: ${error.message}" }
                onShowError("Cannot play this audio file. Format may be unsupported.")
                player.pause()
                false
            }

            is ClassifiedError.Stuck -> {
                // Media3 1.9.0 stuck player detection
                // Player was buffering or ready but not making progress
                logger.warn { "Stuck player detected: ${error.message}" }

                // For HLS progressive transcoding, this might happen during long
                // segment waits. Try to recover by re-preparing.
                val currentPosition = player.currentPosition
                val currentMediaItemIndex = player.currentMediaItemIndex

                player.stop()
                player.prepare()

                // Restore position if we had one
                if (currentPosition > 0) {
                    player.seekTo(currentMediaItemIndex, currentPosition)
                }

                player.play()
                logger.info { "Attempting recovery from stuck state at position $currentPosition" }
                true // Continue, we're attempting recovery
            }

            is ClassifiedError.Unknown -> {
                logger.error(error.cause) { "Unknown playback error" }
                onShowError("Playback error. Please try again.")
                player.pause()
                false
            }
        }
    }

    /**
     * Get a user-friendly message for an error.
     */
    fun getErrorMessage(error: ClassifiedError): String =
        when (error) {
            is ClassifiedError.Network -> "Connection lost. Retrying..."
            is ClassifiedError.AuthExpired -> "Session expired. Please sign in."
            is ClassifiedError.NotFound -> "File not available."
            is ClassifiedError.Codec -> "Cannot play this format."
            is ClassifiedError.Stuck -> "Playback stuck. Retrying..."
            is ClassifiedError.Unknown -> "Playback error."
        }
}
