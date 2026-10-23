package com.calypsan.listenup.client.presentation.player

import com.calypsan.listenup.client.playback.SleepTimerMode
import com.calypsan.listenup.client.playback.SleepTimerState

/**
 * How long a running sleep timer has left, and how full its meter is (spec §5.5.2, §5.6), decided once so every
 * countdown agrees: the settings control, the mini line, the sheet's running view and its "Pauses at".
 *
 * Always **wall time**: a timer is about the clock, and "Pauses at 11:42 PM" must be right. A duration timer's own
 * remainder already is; an end-of-chapter timer's is the chapter's content remainder ÷ the listening speed. (The end-
 * of-chapter timer carries no countdown of its own — `SleepTimerManager` arms it with `remainingMs = 0` — which is why
 * surfaces must never read `SleepTimerState.Active.remainingMs` directly.) An object, so Swift sees `SleepCountdowns.shared`.
 */
object SleepCountdowns {
    /** Wall-clock milliseconds until [timer] pauses playback, or null when no timer is counting down. Pure. */
    fun remainingMs(
        timer: SleepTimerState,
        progress: PlayerProgress,
        speed: Float,
    ): Long? {
        val active = timer as? SleepTimerState.Active ?: return null
        return remainingMs(
            isEndOfChapter = active.mode is SleepTimerMode.EndOfChapter,
            durationRemainingMs = active.remainingMs,
            chapterRemainingMs = progress.chapterRemainingMs,
            speed = speed,
        )
    }

    /** The same rule over plain values, for Swift, which holds native copies of the timer and the progress. Pure. */
    fun remainingMs(
        isEndOfChapter: Boolean,
        durationRemainingMs: Long,
        chapterRemainingMs: Long,
        speed: Float,
    ): Long =
        if (isEndOfChapter) {
            if (speed > 0f) (chapterRemainingMs / speed).toLong() else chapterRemainingMs
        } else {
            durationRemainingMs.coerceAtLeast(0L)
        }

    /** The share of the timer still to run, 0..1: what the gold meter draws. 0 with no timer. Pure. */
    fun meterFraction(
        timer: SleepTimerState,
        progress: PlayerProgress,
    ): Float {
        val active = timer as? SleepTimerState.Active ?: return 0f
        return when (active.mode) {
            is SleepTimerMode.Duration -> share(active.remainingMs, active.totalMs)
            SleepTimerMode.EndOfChapter -> share(progress.chapterRemainingMs, progress.chapterDurationMs)
        }
    }

    private fun share(
        part: Long,
        whole: Long,
    ): Float = if (whole > 0) (part.toFloat() / whole).coerceIn(0f, 1f) else 0f
}
