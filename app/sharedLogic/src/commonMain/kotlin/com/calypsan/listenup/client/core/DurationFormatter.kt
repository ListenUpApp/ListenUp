package com.calypsan.listenup.client.core

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * The single home for duration → display-string formatting, replacing the
 * hand-rolled copies previously scattered across domain models, ViewModels,
 * and Compose screens. Each function is one deliberate format FAMILY — the
 * families differ on purpose (see each KDoc); output is character-identical
 * to the pre-consolidation call sites, pinned by `DurationFormatterTest`.
 *
 * Inputs are [kotlin.time.Duration]; callers convert raw Long fields at the
 * call site (`ms.milliseconds`, `seconds.seconds`, `minutes.minutes`).
 * Negative durations clamp to zero. Unit labels ("h"/"m") are intentionally
 * NOT localized — matching the pre-existing behavior of every migrated call
 * site; the catalog-routed labels (e.g. home_hours_minutes) are separate.
 */
object DurationFormatter {
    /** `"2h 5m"` / `"45m"`; hours dropped when zero; sub-minute floors to `"0m"`. */
    fun hoursMinutes(duration: Duration): String {
        val hours = duration.wholeHours()
        val minutes = duration.minutesInHour()
        return if (hours > 0) hoursAndMinutes(hours, minutes) else minutesOnly(minutes)
    }

    /** `"0h 45m"` — hours segment ALWAYS present (book-detail readout). */
    fun hoursMinutesAlways(duration: Duration): String =
        hoursAndMinutes(duration.wholeHours(), duration.minutesInHour())

    /** `"2h"` / `"45m"` / `"2h 5m"` / `"0m"` — zero components dropped (shelf chips). */
    fun hoursMinutesCompact(duration: Duration): String {
        val hours = duration.wholeHours()
        val minutes = duration.minutesInHour()
        return when {
            hours > 0 && minutes > 0 -> hoursAndMinutes(hours, minutes)
            hours > 0 -> "${hours}h"
            minutes > 0 -> minutesOnly(minutes)
            else -> "0m"
        }
    }

    /** `"2h 5m"` / `"45m"` / `"< 1m"` — sub-minute floor variant (playback notification). */
    fun hoursMinutesOrUnderMinute(duration: Duration): String {
        val hours = duration.wholeHours()
        val minutes = duration.minutesInHour()
        return when {
            hours > 0 -> hoursAndMinutes(hours, minutes)
            minutes > 0 -> minutesOnly(minutes)
            else -> "< 1m"
        }
    }

    /** `"01:05"` / `"125:05"` — MM:SS clock; minutes never roll into hours (chapter rows). */
    fun minutesSecondsClock(duration: Duration): String {
        val clamped = duration.clampToZero()
        val minutes = clamped.inWholeMinutes
        val seconds = clamped.inWholeSeconds % 60
        return "${minutes.pad2()}:${seconds.pad2()}"
    }

    /** `"Almost done"` / `"45 min left"` / `"1 hr 30 min left"` / `"2h 15m left"`. */
    fun timeLeft(duration: Duration): String {
        val totalMinutes = duration.inWholeMinutes
        val hours = duration.inWholeHours
        val minutes = totalMinutes % 60
        return when {
            duration < 5.minutes -> "Almost done"
            duration < 1.hours -> "$totalMinutes min left"
            hours < 2 -> "1 hr $minutes min left"
            else -> "${hours}h ${minutes}m left"
        }
    }

    private fun hoursAndMinutes(
        hours: Long,
        minutes: Long,
    ): String = "${hours}h ${minutes}m"

    private fun minutesOnly(minutes: Long): String = "${minutes}m"

    private fun Long.pad2(): String = toString().padStart(2, '0')

    private fun Duration.wholeHours(): Long = clampToZero().inWholeHours

    private fun Duration.minutesInHour(): Long = clampToZero().inWholeMinutes % 60

    private fun Duration.clampToZero(): Duration = if (isNegative()) Duration.ZERO else this
}
