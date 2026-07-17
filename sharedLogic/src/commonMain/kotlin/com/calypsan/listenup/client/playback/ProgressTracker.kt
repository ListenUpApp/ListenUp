@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.domain.repository.PlaybackUpdate
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private val logger = KotlinLogging.logger {}

/**
 * Coordinates position persistence and playback-session tracking.
 *
 * Position is sacred: saves immediately on every pause/seek. Listening-event
 * history is owned exclusively by the canonical recording path
 * ([ListeningEventRecorder]) — this tracker never records listening events.
 *
 * Note on `open`: this class (and its overridable methods below) are `open`
 * solely so seam-level tests can substitute a hand-rolled
 * [com.calypsan.listenup.client.test.fake.FakeProgressTracker] (see Testing
 * rubric: "seam-level tests use fakes with in-memory state, not mocks"). No
 * production subclasses exist; revert to `class`/`fun` once
 * [ProgressTracker] lives behind an interface.
 */
open class ProgressTracker(
    private val downloadRepository: DownloadRepository,
    private val positionRepository: PlaybackPositionRepository,
    private val scope: CoroutineScope,
    /**
     * Wall-clock read seam. Defaults to the system clock; tests inject a virtual clock so
     * session-start/pause/finish timestamps are deterministic.
     */
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    val sessionState: StateFlow<SessionState>
        field = MutableStateFlow<SessionState>(SessionState.Idle)

    /**
     * Called when playback starts/resumes.
     */
    open fun onPlaybackStarted(
        bookId: BookId,
        positionMs: Long,
        speed: Float,
    ) {
        val now = nowMillis()
        sessionState.value =
            SessionState.Active(
                bookId = bookId,
                playbackStartPositionMs = positionMs,
                playbackStartedAt = now,
                speed = speed,
            )
        logger.info { "🎧 LISTENING SESSION STARTED: book=${bookId.value}, position=$positionMs, speed=$speed" }

        // Save position immediately so the book appears in Continue Listening right away
        // This ensures even brief playback sessions are tracked.
        // Uses PlaybackStarted (not PeriodicUpdate) so the handler can insert a new row
        // when none exists (never-played book first-play).
        scope.launch {
            when (
                val r =
                    positionRepository.savePlaybackState(
                        bookId = bookId,
                        update = PlaybackUpdate.PlaybackStarted(positionMs = positionMs, speed = speed),
                    )
            ) {
                is AppResult.Success -> {
                    logger.debug { "Initial position recorded: book=${bookId.value}" }
                }

                is AppResult.Failure -> {
                    logger.warn {
                        "Failed to record initial position for ${bookId.value}: ${r.error.message}"
                    }
                }
            }
        }
    }

    /**
     * Called when playback pauses/stops.
     * Saves position immediately.
     */
    open fun onPlaybackPaused(
        bookId: BookId,
        positionMs: Long,
        speed: Float,
    ) {
        val now = nowMillis()

        // Atomic transition: Active(matching bookId) -> Paused; else no-op
        val priorState = sessionState.value // capture for transition logging
        sessionState.update { current ->
            if (current is SessionState.Active && current.bookId == bookId) {
                SessionState.Paused(
                    bookId = current.bookId,
                    playbackStartPositionMs = current.playbackStartPositionMs,
                    playbackStartedAt = current.playbackStartedAt,
                    pausedAt = now,
                    speed = current.speed,
                )
            } else {
                current
            }
        }

        logPausedTransition(bookId, positionMs, priorState)

        scope.launch {
            // Save position immediately
            savePosition(bookId, positionMs, speed)
        }
    }

    private fun logPausedTransition(
        bookId: BookId,
        positionMs: Long,
        priorState: SessionState,
    ) {
        when (priorState) {
            is SessionState.Active -> {
                if (priorState.bookId != bookId) {
                    logger.warn { "🎧 PAUSE BOOK MISMATCH: state=${priorState.bookId.value}, paused=${bookId.value}" }
                }
            }

            is SessionState.Paused -> {
                logger.warn { "🎧 PAUSE FROM PAUSED — no-op" }
            }

            SessionState.Idle -> {
                logger.warn { "🎧 PAUSE FROM IDLE — no-op" }
            }
        }
        logger.info {
            "🎧 PLAYBACK PAUSED: book=${bookId.value}, position=$positionMs, prior=${priorState::class.simpleName}"
        }
    }

    /**
     * Called periodically during playback (every 30 seconds).
     * Updates local position so the user's place is never lost.
     */
    fun onPositionUpdate(
        bookId: BookId,
        positionMs: Long,
        speed: Float,
    ) {
        scope.launch {
            // Save position locally
            savePosition(bookId, positionMs, speed)
        }
    }

    /**
     * Save position via the repository seam.
     * Routes through [PlaybackPositionRepository.savePlaybackState] with [PlaybackUpdate.PeriodicUpdate].
     *
     * @param bookId The book to save position for
     * @param positionMs Current position in milliseconds
     * @param speed Current playback speed
     */
    private suspend fun savePosition(
        bookId: BookId,
        positionMs: Long,
        speed: Float,
    ) {
        when (
            val r =
                positionRepository.savePlaybackState(
                    bookId = bookId,
                    update = PlaybackUpdate.PeriodicUpdate(positionMs = positionMs, speed = speed),
                )
        ) {
            is AppResult.Success -> logger.info { "Position saved: book=${bookId.value}, position=$positionMs" }
            is AppResult.Failure -> logger.warn { "Failed to save position for ${bookId.value}: ${r.error.message}" }
        }
    }

    /**
     * Called when user explicitly changes playback speed.
     * Marks this book as having a custom speed (not using universal default).
     */
    fun onSpeedChanged(
        bookId: BookId,
        positionMs: Long,
        newSpeed: Float,
    ) {
        // Update session speed atomically if active/paused for this book
        sessionState.update { current ->
            when {
                current is SessionState.Active && current.bookId == bookId -> current.copy(speed = newSpeed)
                current is SessionState.Paused && current.bookId == bookId -> current.copy(speed = newSpeed)
                else -> current
            }
        }

        scope.launch {
            when (
                val r =
                    positionRepository.savePlaybackState(
                        bookId = bookId,
                        update = PlaybackUpdate.Speed(positionMs = positionMs, speed = newSpeed, custom = true),
                    )
            ) {
                is AppResult.Success -> logger.debug { "Speed changed: book=${bookId.value}, speed=$newSpeed" }
                is AppResult.Failure -> logger.warn { "Failed to change speed for ${bookId.value}: ${r.error.message}" }
            }
        }
    }

    /**
     * Reset a book's speed to use the universal default.
     * Called when user explicitly resets to default.
     */
    fun onSpeedReset(
        bookId: BookId,
        positionMs: Long,
        defaultSpeed: Float,
    ) {
        // Update session speed atomically if active/paused for this book
        sessionState.update { current ->
            when {
                current is SessionState.Active && current.bookId == bookId -> current.copy(speed = defaultSpeed)
                current is SessionState.Paused && current.bookId == bookId -> current.copy(speed = defaultSpeed)
                else -> current
            }
        }

        scope.launch {
            when (
                val r =
                    positionRepository.savePlaybackState(
                        bookId = bookId,
                        update = PlaybackUpdate.SpeedReset(positionMs = positionMs, defaultSpeed = defaultSpeed),
                    )
            ) {
                is AppResult.Success -> logger.debug { "Speed reset: book=${bookId.value}, speed=$defaultSpeed" }
                is AppResult.Failure -> logger.warn { "Failed to reset speed for ${bookId.value}: ${r.error.message}" }
            }
        }
    }

    /**
     * Save position immediately (blocking for critical saves).
     * Used before error handling to ensure position is never lost.
     */
    suspend fun savePositionNow(
        bookId: BookId,
        positionMs: Long,
    ) {
        val speed =
            when (val s = sessionState.value) {
                is SessionState.Active -> s.speed
                is SessionState.Paused -> s.speed
                SessionState.Idle -> 1.0f
            }
        savePosition(bookId, positionMs, speed)
    }

    /**
     * Get resume position for a book.
     *
     * Reads the local Room row — kept current by
     * [com.calypsan.listenup.client.data.sync.domains.playbackPositionsDomain] (live
     * SSE events) and catch-up on reconnect. The synchronous HTTP read that
     * previously blocked ExoPlayer startup has been removed: `PlaybackService.prepare`
     * returns the server-authoritative resume position in the same call that signs the
     * stream URLs, so there is no need for a separate progress read at resume time.
     *
     * @param bookId Book to get resume position for
     * @return Position to resume from, or null if never played
     */
    open suspend fun getResumePosition(bookId: BookId): PlaybackPosition? {
        val result = positionRepository.get(bookId)
        return if (result is AppResult.Success) result.data else null
    }

    /**
     * Mark a book as finished.
     * Called when playback reaches the end.
     *
     * @param bookId The book that finished
     * @param finalPositionMs The final position (typically the book's total duration)
     */
    fun onBookFinished(
        bookId: BookId,
        finalPositionMs: Long,
    ) {
        sessionState.update { _ -> SessionState.Idle }

        scope.launch {
            logger.info { "Book finished: ${bookId.value}, finalPosition=$finalPositionMs" }

            // Clear only DELETED tombstones so a re-listen auto-downloads again (default stream +
            // download behavior). COMPLETED rows and their local files MUST survive — wiping them
            // here would orphan the multi-GB files on disk and force every future play to stream,
            // failing offline. This is the "finishing a book destroys its offline copy" fix.
            try {
                downloadRepository.deleteDeletedRecordsForBook(bookId.value)
                logger.debug { "Cleared DELETED download tombstones for finished book: ${bookId.value}" }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn { "Failed to clear download tombstones for ${bookId.value} (non-fatal): ${e.message}" }
            }

            // Mark book as complete
            val finishedAt = nowMillis()
            when (
                val r =
                    positionRepository.markComplete(
                        bookId = bookId,
                        startedAt = null,
                        finishedAt = finishedAt,
                    )
            ) {
                is AppResult.Success -> {
                    logger.info { "Book marked complete: ${bookId.value}" }
                }

                is AppResult.Failure -> {
                    logger.warn {
                        "Failed to mark book ${bookId.value} complete: ${r.error.message}"
                    }
                }
            }
        }
    }

    /**
     * Clear progress for a book (reset to beginning).
     */
    suspend fun clearProgress(bookId: BookId) {
        when (val r = positionRepository.delete(bookId)) {
            is AppResult.Success -> {
                logger.info { "Progress cleared for book: ${bookId.value}" }
            }

            is AppResult.Failure -> {
                logger.warn {
                    "Failed to clear progress for book ${bookId.value}: ${r.error.message}"
                }
            }
        }
    }

    /**
     * Get the current session's playback speed.
     * Returns 1.0 if no active session.
     */
    fun getCurrentSpeed(): Float =
        when (val s = sessionState.value) {
            is SessionState.Active -> s.speed
            is SessionState.Paused -> s.speed
            SessionState.Idle -> 1.0f
        }
}
