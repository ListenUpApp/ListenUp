package com.calypsan.listenup.client.core

import kotlin.math.abs

private const val MS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val MINUTES_PER_HOUR = 60L
private const val MS_PER_MINUTE = MS_PER_SECOND * SECONDS_PER_MINUTE
private const val MS_PER_HOUR = MS_PER_MINUTE * MINUTES_PER_HOUR
private const val TENTHS_PER_SECOND = 10L
private const val HUNDREDTHS_PER_SECOND = 100L

/**
 * Absolute positions inside a book, at the precision the chapter editor works in.
 *
 * `DurationFormatter` renders *durations* for reading ("3h 20m"); these render *positions* for
 * editing, where the digits are the thing being manipulated. Different job, so a different
 * function rather than another mode bolted onto the first.
 *
 * Shared rather than per-platform because all three clients show the same number against the same
 * boundary, and a book that reads `41:12:08.4` on the phone and `41:12:8.40` on the web is the
 * kind of difference that makes people distrust the whole editor. Zero-padding, the hour field
 * growing past 24, and where the decimal point falls are all decisions, and they are made once.
 */
object ChapterTimeFormat {
    /**
     * `41:12:08` — the transport readout.
     *
     * Hours are not wrapped at 24: a 65-hour book genuinely has an `h` field of 41, and rolling it
     * over would address the wrong instant.
     */
    fun clock(ms: Long): String = render(ms, decimals = 0)

    /** `41:12:08.4` — a chapter's start in the list, where a tenth is as fine as the eye needs. */
    fun precise(ms: Long): String = render(ms, decimals = 1)

    /**
     * `41:12:08.42` — the fine-scrub HUD and snap-to-playhead.
     *
     * Hundredths because this is the readout the user is aiming *with*: it has to move visibly as
     * they pull the drag finer, or the gesture gives no feedback that it is working.
     */
    fun exact(ms: Long): String = render(ms, decimals = 2)

    /**
     * `+0:03.2` / `−0:47.9` — a drift anchor's offset from where the scrape put it.
     *
     * Signed and minute-scaled, because drift is read as "how far out is this", not as a position.
     * A true minus sign rather than a hyphen: these sit next to each other in the anchor cards and
     * a hyphen is visibly shorter than the plus it lines up against.
     */
    fun offset(ms: Long): String {
        val sign = if (ms < 0) "−" else "+"
        val magnitude = abs(ms)
        val minutes = magnitude / MS_PER_MINUTE
        val withinMinute = magnitude % MS_PER_MINUTE
        // Integer arithmetic, not doubles: 47_900ms as a Double is 47.899999…, and truncating that
        // renders "+0:47.8" for a value that is exactly 47.9. The digit people read the drift off
        // would be wrong by a tenth, consistently, and plausibly.
        val seconds = withinMinute / MS_PER_SECOND
        val subSecond = withinMinute % MS_PER_SECOND
        val tenths = subSecond / (MS_PER_SECOND / TENTHS_PER_SECOND)
        return "$sign$minutes:${pad(seconds)}.$tenths"
    }

    private fun render(
        ms: Long,
        decimals: Int,
    ): String {
        val sign = if (ms < 0) "-" else ""
        val magnitude = abs(ms)
        val hours = magnitude / MS_PER_HOUR
        val withinHour = magnitude % MS_PER_HOUR
        val withinMinute = magnitude % MS_PER_MINUTE
        val minutes = withinHour / MS_PER_MINUTE
        val seconds = withinMinute / MS_PER_SECOND
        val base = "$sign$hours:${pad(minutes)}:${pad(seconds)}"
        val remainder = magnitude % MS_PER_SECOND
        return when (decimals) {
            1 -> "$base.${remainder / (MS_PER_SECOND / TENTHS_PER_SECOND)}"
            2 -> "$base.${pad(remainder / (MS_PER_SECOND / HUNDREDTHS_PER_SECOND))}"
            else -> base
        }
    }

    private fun pad(value: Long): String = value.toString().padStart(2, '0')
}
