package com.calypsan.listenup.domain

/**
 * The one definition of "this book is finished", read by the server (ABS import) and every client
 * (playback, Home, Series). Before it existed four rules disagreed — 0.90, 0.95, 0.99 and flag-only
 * — so a book could be finished on the Series page and still in Continue Listening on Home.
 *
 * The flag is authoritative because it only ever means one thing: starting a re-listen clears it
 * (the preparer issues a Restart), so a set flag is never a book someone is partway through again.
 */
object FinishedPolicy {
    /**
     * At or beyond this fraction of the duration a book counts as finished even without the flag —
     * a listener who stops during the end credits has finished the story.
     */
    const val COMPLETION_FRACTION: Double = 0.99

    /**
     * Below this fraction a player's end-of-media signal is treated as spurious (some players
     * report "ended" on release or stop) and must not mark the book finished.
     */
    const val ENDED_SIGNAL_MIN_FRACTION: Double = 0.90

    /** [positionMs] as a fraction of [durationMs], clamped to 0..1; `0.0` when the duration is unknown. */
    fun fraction(
        positionMs: Long,
        durationMs: Long,
    ): Double = if (durationMs > 0L) (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0) else 0.0

    /** True when [flag] is set or the listener is at or beyond [COMPLETION_FRACTION]. */
    fun isFinished(
        positionMs: Long,
        durationMs: Long,
        flag: Boolean,
    ): Boolean = isFinished(fraction(positionMs, durationMs), flag)

    /** [isFinished] for callers that already hold a progress fraction (the ABS importer). */
    fun isFinished(
        progressFraction: Double,
        flag: Boolean,
    ): Boolean = flag || progressFraction >= COMPLETION_FRACTION

    /** True when an end-of-media signal at [positionMs] is plausible enough to mark the book finished. */
    fun acceptsEndedSignal(
        positionMs: Long,
        durationMs: Long,
    ): Boolean = durationMs > 0L && fraction(positionMs, durationMs) >= ENDED_SIGNAL_MIN_FRACTION
}
