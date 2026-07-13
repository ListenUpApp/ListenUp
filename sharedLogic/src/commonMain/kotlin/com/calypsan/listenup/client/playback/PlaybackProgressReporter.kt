package com.calypsan.listenup.client.playback

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * The single playback-session signal seam for the platforms whose playback core lives
 * in commonMain — iOS (via the native `PlayerCoordinator` → `KotlinProgressReporting`
 * adapter) and Desktop/macOS (via [PlaybackManagerImpl]). It fans every session signal
 * out to BOTH concerns that care about it:
 *
 *  1. **Position persistence** — delegated to [ProgressTracker] (never lose the user's place).
 *  2. **Listening-event recording** — delegated to [ListeningEventRecorder], the canonical
 *     span recorder that stamps events with the account user id and enqueues a server
 *     sync op.
 *
 * **Why this exists.** Before this seam, only Android drove [ListeningEventRecorder] (from
 * its Media3 `PlaybackService`); iOS and Desktop drove only [ProgressTracker], so their
 * listening history never reached the server. Routing both through one reporter fixes that
 * without duplicating the trigger→recorder mapping across platforms.
 *
 * **Android opts out.** Android's `PlaybackService` already drives [ListeningEventRecorder]
 * directly to integrate with Media3's player lifecycle. Android therefore binds this reporter
 * with a `null` [recorder] so the same playback signals — which Android *also* routes through
 * [PlaybackManagerImpl] via `MediaControllerHolder` — do not record a second time.
 *
 * **Trigger set.** Mirrors what Android records today: open on play/resume, advance on each
 * heartbeat tick, finalize on pause and on book finish, and **split the span on a seek** (see
 * [onSeek]) so a jumped-over range is never counted as listened content. Speed-change does not
 * split the span — the span keeps its opening speed, which is metadata only; stats derive
 * listening time from wall-clock and content from positions, both accurate regardless.
 *
 * @property progressTracker Position-persistence collaborator; always driven.
 * @property recorder Listening-event recorder; `null` on Android, non-null on
 *   iOS/Desktop/macOS. When `null`, every recording call is skipped and only [progressTracker]
 *   runs.
 * @property scope Scope on which the recorder's suspend calls are launched, mirroring
 *   [ProgressTracker]'s own fire-and-forget style. Recording failures are non-fatal (the
 *   recorder logs and self-heals via orphan recovery), so they never block playback.
 */
class PlaybackProgressReporter(
    private val progressTracker: ProgressTracker,
    private val recorder: ListeningEventRecorder?,
    private val scope: CoroutineScope,
) {
    /** Playback started or resumed: save the position and open a listening span. */
    fun onPlaybackStarted(
        bookId: BookId,
        positionMs: Long,
        speed: Float,
    ) {
        progressTracker.onPlaybackStarted(bookId, positionMs, speed)
        record { it.onPlay(bookId.value, positionMs, speed) }
    }

    /** Playback paused or stopped: save the position and finalize the open span. */
    fun onPlaybackPaused(
        bookId: BookId,
        positionMs: Long,
        speed: Float,
    ) {
        progressTracker.onPlaybackPaused(bookId, positionMs, speed)
        record { it.onPause(positionMs) }
    }

    /** Periodic heartbeat during playback: save the position and extend the open span. */
    fun onPositionUpdate(
        bookId: BookId,
        positionMs: Long,
        speed: Float,
    ) {
        progressTracker.onPositionUpdate(bookId, positionMs, speed)
        record { it.onPeriodicTick(positionMs) }
    }

    /**
     * User seeked within the book: persist the post-seek position and SPLIT the listening span so
     * the jumped-over range is not counted as listened content. Finalizes the pre-seek span at
     * [beforeMs] and opens a fresh one at [afterMs] (see [ListeningEventRecorder.onSeek]). Without
     * this, a seek routed through [onPositionUpdate] would inflate a single span to span the jump,
     * fabricating content coverage that corrupts the books-finished / coverage-derived stats.
     */
    fun onSeek(
        bookId: BookId,
        beforeMs: Long,
        afterMs: Long,
        speed: Float,
    ) {
        progressTracker.onPositionUpdate(bookId, afterMs, speed)
        record { it.onSeek(positionBeforeSeek = beforeMs, positionAfterSeek = afterMs) }
    }

    /** User changed playback speed for this book. Speed is not span-split (see class KDoc). */
    fun onSpeedChanged(
        bookId: BookId,
        positionMs: Long,
        newSpeed: Float,
    ) = progressTracker.onSpeedChanged(bookId, positionMs, newSpeed)

    /** User reset this book's speed to the universal default. Not span-split (see class KDoc). */
    fun onSpeedReset(
        bookId: BookId,
        positionMs: Long,
        defaultSpeed: Float,
    ) = progressTracker.onSpeedReset(bookId, positionMs, defaultSpeed)

    /**
     * Playback reached the end of the book: mark it complete and finalize the open span at
     * [finalPositionMs]. The recorder's [ListeningEventRecorder.onPause] is the "finalize the
     * current span at this position" operation; book finish is just another trigger for it.
     * This is required because iOS does not fire a pause at a natural end — without it the span
     * would be left open and only recovered (lossily) on the next launch.
     */
    fun onBookFinished(
        bookId: BookId,
        finalPositionMs: Long,
    ) {
        progressTracker.onBookFinished(bookId, finalPositionMs)
        record { it.onPause(finalPositionMs) }
    }

    /** Resume position read at prepare time — pure position concern, no recording. */
    suspend fun getResumePosition(bookId: BookId): PlaybackPosition? = progressTracker.getResumePosition(bookId)

    /** Durable position save for lifecycle teardown — pure position concern, no recording. */
    suspend fun savePositionNow(
        bookId: BookId,
        positionMs: Long,
    ) = progressTracker.savePositionNow(bookId, positionMs)

    /**
     * Launch a recorder action on [scope] when a [recorder] is bound; skip silently otherwise
     * (Android). Mirrors [ProgressTracker]'s fire-and-forget style so a slow or failing write
     * never blocks the playback signal that triggered it.
     */
    private inline fun record(crossinline action: suspend (ListeningEventRecorder) -> Unit) {
        val recorder = recorder ?: return
        scope.launch {
            try {
                action(recorder)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "[PlaybackProgressReporter] Listening-event recording failed (non-fatal)" }
            }
        }
    }
}
