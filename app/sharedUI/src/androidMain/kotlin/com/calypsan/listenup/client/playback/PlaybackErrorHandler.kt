package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.AppError
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import com.calypsan.listenup.core.BookId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404

/**
 * Backoff before each successive recovery attempt, and — by its length — the retry budget.
 *
 * Sized for the case that motivates it: walking through a dead spot. The five attempts span
 * roughly half a minute of lost signal, which covers a tunnel or a lift without holding a wake
 * lock open indefinitely against a network that is genuinely gone. When the budget is spent the
 * listener is told, and the manual path in front of them works — see [needsPrepareBeforePlay].
 */
private val RECOVERY_BACKOFF_MS = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L)

/** How many automatic recovery attempts a single stall gets before the listener is told. */
internal val RECOVERY_ATTEMPT_BUDGET = RECOVERY_BACKOFF_MS.size

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
    private val tokenProvider: AudioTokenRecovery,
) {
    /**
     * Recovery attempts spent since playback was last healthy.
     *
     * Atomic because it is read from the service's coroutine scope and reset from Media3's
     * player callbacks on the main looper.
     */
    private val recoveryAttempts = AtomicInteger(0)

    /**
     * Refills the recovery budget — call when playback is confirmed healthy again.
     *
     * Without this the budget is per-session rather than per-incident: a dead spot on the morning
     * walk would silently consume the retries needed by an unrelated stall that evening.
     */
    fun onPlaybackHealthy() {
        recoveryAttempts.set(0)
    }

    /**
     * Classifies errors into actionable categories.
     */
    sealed interface ClassifiedError {
        // Retryable - ExoPlayer handles internally, we just wait
        data class Network(
            val message: String,
        ) : ClassifiedError

        // Retryable once - refresh token, retry request
        data class AuthExpired(
            val message: String,
        ) : ClassifiedError

        // Not retryable - user action required
        data class NotFound(
            val message: String,
        ) : ClassifiedError

        data class Codec(
            val message: String,
        ) : ClassifiedError

        // Stuck player - Media3 1.9.0 detects when playback is stuck
        // Triggers after 10 min buffering, 10s ready with no progress, etc.
        data class Stuck(
            val message: String,
        ) : ClassifiedError

        data class Unknown(
            val cause: Throwable,
        ) : ClassifiedError
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
        onShowError: (AppError) -> Unit,
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
                // ExoPlayer's own load-error retries are already spent by the time onPlayerError
                // fires — the player is IDLE and nothing is buffering. Re-preparing is the only
                // thing that resumes playback; without it this branch is a silent no-op.
                recoverOrGiveUp(player, onShowError, "network") { idle ->
                    idle.prepare()
                    idle.play()
                }
            }

            is ClassifiedError.AuthExpired -> {
                logger.warn { "Auth expired during playback" }

                // The credential the server just rejected. A failed refresh re-caches this very
                // value — the provider falls back to whatever is stored — so "a token exists"
                // proves nothing. Only a *different* token is evidence anything changed, which is
                // the same test AudioTokenAuthenticator applies on the OkHttp side.
                val rejectedToken = tokenProvider.currentToken()
                tokenProvider.refresh()
                val refreshedToken = tokenProvider.currentToken()

                if (refreshedToken == null || refreshedToken == rejectedToken) {
                    logger.warn { "Token refresh produced no new token; the listener must sign in" }
                    onShowError(AuthError.SessionExpired(debugInfo = "audio token refresh failed"))
                    player.pause()
                    false
                } else {
                    // Budget-bounded even on success: a server that keeps issuing fresh tokens
                    // which are still rejected walks past the guard above on every attempt.
                    logger.info { "Token refreshed, retrying playback" }
                    recoverOrGiveUp(player, onShowError, "auth") { authed ->
                        authed.prepare()
                        authed.play()
                    }
                }
            }

            is ClassifiedError.NotFound -> {
                logger.error { "Audio file not found: ${error.message}" }
                // No §4.6 row for a vanished file: it cannot start (plan Decision 9).
                onShowError(PlaybackError.CouldNotStart(debugInfo = "audio file 404"))
                player.pause()
                false
            }

            is ClassifiedError.Codec -> {
                logger.error { "Codec error: ${error.message}" }
                onShowError(PlaybackError.UnsupportedFormat(debugInfo = error.message))
                player.pause()
                false
            }

            is ClassifiedError.Stuck -> {
                // Media3's stuck-player detection (see StallRecovery.kt for the timeout we set).
                // Shares the retry budget with the network path deliberately: a book that stalls,
                // recovers, and stalls again is failing repeatedly whatever the reported cause,
                // and each recovery here is a full stop/prepare/seek cycle.
                logger.warn { "Stuck player detected: ${error.message}" }

                recoverOrGiveUp(player, onShowError, "stuck") { stuck ->
                    // Re-preparing resets the position, so capture it first and restore it after.
                    val currentPosition = stuck.currentPosition
                    val currentMediaItemIndex = stuck.currentMediaItemIndex

                    stuck.stop()
                    stuck.prepare()

                    if (currentPosition > 0) {
                        stuck.seekTo(currentMediaItemIndex, currentPosition)
                    }

                    stuck.play()
                    logger.info { "Attempting recovery from stuck state at position $currentPosition" }
                }
            }

            is ClassifiedError.Unknown -> {
                logger.error(error.cause) { "Unknown playback error" }
                onShowError(PlaybackError.CouldNotStart(debugInfo = error.cause.toString()))
                player.pause()
                false
            }
        }
    }

    /**
     * Spends one unit of the recovery budget on [recover], or gives up and tells the listener.
     *
     * Backs off before acting: an immediate re-prepare against a network that has not returned
     * just burns the budget in a few hundred milliseconds. Returns `true` while attempts remain
     * (playback is being recovered), `false` once the budget is spent.
     *
     * Nothing plays while the listener is told it is broken. A player paused by the time the
     * backoff ends (the shared player stops it the moment a failure surfaces, or the listener
     * paused) is not recovered: only Retry or an explicit Play starts it again. A spent budget
     * pauses the player for the same reason, so no later Media3 retry can resume it.
     */
    private suspend fun recoverOrGiveUp(
        player: ExoPlayer,
        onShowError: (AppError) -> Unit,
        reason: String,
        recover: (ExoPlayer) -> Unit,
    ): Boolean {
        val attempt = recoveryAttempts.getAndIncrement()

        if (attempt >= RECOVERY_BACKOFF_MS.size) {
            logger.warn { "Recovery budget spent after $attempt attempts ($reason); giving up" }
            onShowError(PlaybackError.ConnectionLost(debugInfo = "recovery budget spent: $reason"))
            player.pause()
            return false
        }

        val backoff = RECOVERY_BACKOFF_MS[attempt]
        logger.info { "Recovering playback ($reason), attempt ${attempt + 1} in ${backoff}ms" }
        delay(backoff)
        if (!player.playWhenReady) {
            logger.info { "Playback was stopped while recovering ($reason); not resuming it" }
            return false
        }
        recover(player)
        return true
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
