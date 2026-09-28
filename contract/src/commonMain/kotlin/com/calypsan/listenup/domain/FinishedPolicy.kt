package com.calypsan.listenup.domain

/**
 * The one definition of "this book is finished", read by the server (ABS import) and every client
 * (playback, Home, Series). Before it existed four rules disagreed — 0.90, 0.95, 0.99 and flag-only
 * — so a book could be finished on the Series page and still in Continue Listening on Home.
 *
 * The flag is authoritative because it only ever means one thing: starting a re-listen clears it
 * (the preparer issues a Restart when a finished book is opened, and the progress tracker does when
 * one plays again outside its end credits), so a set flag is never a book someone is partway through
 * again.
 */
object FinishedPolicy {
    /** The end credits are at most this share of a book… */
    const val CREDITS_WINDOW_FRACTION: Double = 0.01

    /**
     * …and never longer than this. A bare fraction would not do: 1% of a twenty-hour book is twelve
     * minutes of story, and someone who stops there has not finished it.
     */
    const val CREDITS_WINDOW_MAX_MS: Long = 60_000L

    /**
     * Below this fraction a player's end-of-media signal is treated as spurious (some players
     * report "ended" on release or stop) and must not mark the book finished.
     */
    const val ENDED_SIGNAL_MIN_FRACTION: Double = 0.90

    /**
     * True when [flag] is set, or the listener stopped inside the end credits: within the last
     * [CREDITS_WINDOW_FRACTION] of [durationMs] and no more than [CREDITS_WINDOW_MAX_MS] from its end.
     * An unknown duration (`<= 0`) is never finished without the flag.
     */
    fun isFinished(
        positionMs: Long,
        durationMs: Long,
        flag: Boolean,
    ): Boolean {
        if (flag) return true
        if (durationMs <= 0L) return false
        val creditsWindowMs = minOf((durationMs * CREDITS_WINDOW_FRACTION).toLong(), CREDITS_WINDOW_MAX_MS)
        return durationMs - positionMs <= creditsWindowMs
    }

    /** True when an end-of-media signal at [positionMs] is plausible enough to mark the book finished. */
    fun acceptsEndedSignal(
        positionMs: Long,
        durationMs: Long,
    ): Boolean = durationMs > 0L && positionMs.toDouble() / durationMs >= ENDED_SIGNAL_MIN_FRACTION
}
