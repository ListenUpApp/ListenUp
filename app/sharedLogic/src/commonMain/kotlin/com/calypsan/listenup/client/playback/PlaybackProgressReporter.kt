package com.calypsan.listenup.client.playback

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.client.domain.repository.LocalPreferences
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Clock

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
 * **Also the in-session pause→resume auto-rewind seam (#1220).** [notePlaybackPaused] /
 * [notePlaybackResumed] are a SEPARATE pair of methods from the trigger set above — they carry
 * no persistence side effect of their own, so they are safe to call unconditionally from every
 * platform's isPlaying-transition signal, even Android's (which opts OUT of routing
 * [onPlaybackStarted]/[onPlaybackPaused] through this reporter — see [PlaybackManagerImpl]'s
 * `persistTransitionsViaReporter`). [PlaybackManagerImpl.setPlaying] calls them on Android and
 * Desktop. **iOS does not currently call them** — iOS's native `PlayerCoordinator` drives this
 * reporter directly but only for the persisted trigger set; wiring `notePlaybackPaused` /
 * `notePlaybackResumed` into its own play/pause transitions (plus registering
 * [onAutoRewindSeek] and calling [resetAutoRewindWindow] at prepare/book-switch time) is
 * outstanding native work, not yet done as of #1220.
 *
 * @property progressTracker Position-persistence collaborator; always driven.
 * @property recorder Listening-event recorder; `null` on Android, non-null on
 *   iOS/Desktop/macOS. When `null`, every recording call is skipped and only [progressTracker]
 *   runs.
 * @property scope Scope on which the recorder's suspend calls are launched, mirroring
 *   [ProgressTracker]'s own fire-and-forget style. Recording failures are non-fatal (the
 *   recorder logs and self-heals via orphan recovery), so they never block playback.
 * @property localPreferences Read for `autoRewindEnabled` — the same user preference that
 *   gates the prepare-time ladder in [PlaybackPreparer].
 * @property nowMillis Injectable wall clock for the pause-window measurement, mirroring the
 *   `nowMillis` seam already used by [PlaybackPreparer] / [ProgressTracker] / [SleepTimerManager].
 */
class PlaybackProgressReporter(
    private val progressTracker: ProgressTracker,
    private val recorder: ListeningEventRecorder?,
    private val scope: CoroutineScope,
    private val localPreferences: LocalPreferences,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
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

    /** User picked a per-book boost. Fire-and-forget; the AppResult folds inside the tracker. */
    fun onVolumeBoostChanged(
        bookId: BookId,
        positionMs: Long,
        newBoostDb: Float,
    ) = progressTracker.onVolumeBoostChanged(bookId, positionMs, newBoostDb)

    /** User reset this book to the global default boost. */
    fun onBoostReset(
        bookId: BookId,
        positionMs: Long,
        defaultBoostDb: Float,
    ) = progressTracker.onBoostReset(bookId, positionMs, defaultBoostDb)

    /** A refined loudness measurement for this book. Never touches hasCustomBoost. */
    fun onMeasuredGain(
        bookId: BookId,
        positionMs: Long,
        gainDb: Float,
    ) = progressTracker.onMeasuredGain(bookId, positionMs, gainDb)

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

    /**
     * Test-only accessor for the DI-wiring guard tests that resolve this class from the real
     * platform Koin modules and assert the single-recorder invariant (`null` on Android,
     * non-null on iOS/Desktop) — see `androidPlaybackModule` / `desktopPlaybackModule`.
     */
    internal fun recorderForTest(): ListeningEventRecorder? = recorder

    // ── In-session pause→resume auto-rewind (#1220) ──────────────────────────────────────

    /** Wall-clock ms when playback last paused, or `null` when not currently paused (or the
     *  window was cleared by [resetAutoRewindWindow]). */
    private var pausedAtMs: Long? = null

    /**
     * Seeks the active player back by `rewindMs` when [notePlaybackResumed] decides a rewind
     * applies. A TRANSIENT seek, never a persisted position — see [autoRewindMs] KDoc for why.
     * Registered by whichever platform glue owns the real player handle: Android's
     * `PlaybackService` (via the shared `MediaLibrarySession` player), Desktop's
     * [PlaybackManagerImpl.startPlayback] (via its local `AudioPlayer`). `null` until wired;
     * a computed rewind with no actuator registered is silently a no-op.
     */
    var onAutoRewindSeek: ((rewindMs: Long) -> Unit)? = null

    /**
     * Playback paused: record the moment so [notePlaybackResumed] can measure how long the
     * listener was away. See class KDoc for why this is safe to call unconditionally from every
     * platform's isPlaying-transition signal, independent of the persisted trigger set.
     */
    fun notePlaybackPaused() {
        pausedAtMs = nowMillis()
    }

    /**
     * Playback resumed after an in-session pause: apply the same graduated [autoRewindMs] ladder
     * used at prepare-time, backing playback up via [onAutoRewindSeek]. No-op when auto-rewind is
     * disabled, the break was too short to earn a rung, no pause was recorded (including a window
     * cleared by [resetAutoRewindWindow] — see there for why), or no actuator is registered.
     */
    fun notePlaybackResumed() {
        val pausedAt = pausedAtMs ?: return
        pausedAtMs = null
        if (!localPreferences.autoRewindEnabled.value) return
        val rewindMs = autoRewindMs(nowMillis() - pausedAt)
        if (rewindMs > 0) {
            onAutoRewindSeek?.invoke(rewindMs)
        }
    }

    /**
     * Clears the in-session pause window WITHOUT applying a rewind. Call when a book activates
     * (or playback tears down) — otherwise a pause left open on the PREVIOUS book/session would
     * leak a transition rewind onto the next book's first play, stacking on top of the
     * prepare-time offset [PlaybackPreparer] already applied for that book.
     */
    fun resetAutoRewindWindow() {
        pausedAtMs = null
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
