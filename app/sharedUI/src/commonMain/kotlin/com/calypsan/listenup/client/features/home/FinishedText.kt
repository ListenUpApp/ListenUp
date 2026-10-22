package com.calypsan.listenup.client.features.home

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.core.DurationFormatter
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.home_date_range
import listenup.composeapp.generated.resources.home_finished_days_ago
import listenup.composeapp.generated.resources.home_finished_line_no_range
import listenup.composeapp.generated.resources.home_finished_today
import listenup.composeapp.generated.resources.home_finished_yesterday
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

// The finished book's words, shared by Home's finish card and Now Playing's finished card (one formatter, C22).

/** "Finished today", "Finished yesterday" or "Finished N days ago". */
@Composable
internal fun finishedEyebrow(daysAgo: Int): String =
    when (daysAgo) {
        0 -> stringResource(Res.string.home_finished_today)
        1 -> stringResource(Res.string.home_finished_yesterday)
        else -> stringResource(Res.string.home_finished_days_ago, daysAgo)
    }

/** "45h 30m · 6 Sep – 10 Oct", or the length alone when the start is unknown. */
@Composable
internal fun finishedMeta(
    durationMs: Long,
    startedAtMs: Long?,
    finishedAtMs: Long,
    zone: TimeZone,
): String {
    val length = DurationFormatter.hoursMinutes(durationMs.milliseconds)
    // Q8: a finish with no known start (imported from history) shows its length alone.
    startedAtMs ?: return length
    val range =
        stringResource(Res.string.home_date_range, shortDayMonth(startedAtMs, zone), shortDayMonth(finishedAtMs, zone))
    return stringResource(Res.string.home_finished_line_no_range, length, range)
}

/** Local calendar days from [finishedAtMs] to [nowMs] in [zone]: 0 today, 1 yesterday (C10). */
internal fun finishedDaysAgo(
    finishedAtMs: Long,
    nowMs: Long,
    zone: TimeZone,
): Int = localDate(finishedAtMs, zone).daysUntil(localDate(nowMs, zone)).coerceAtLeast(0)

/** "6 Sep": the day, then the short English month, in [zone]. */
internal fun shortDayMonth(
    epochMs: Long,
    zone: TimeZone,
): String {
    val date = localDate(epochMs, zone)
    return "${date.day} ${SHORT_MONTHS[date.month.number - 1]}"
}

private fun localDate(
    epochMs: Long,
    zone: TimeZone,
) = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone).date

private val SHORT_MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
