package com.calypsan.listenup.client.presentation.home

import com.calypsan.listenup.client.domain.DayBucket
import com.calypsan.listenup.client.domain.GenreShare
import kotlin.time.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

/**
 * One column of the Home "this week" chart, ready to draw: the label under the bar, the seconds it
 * represents, and whether it is today.
 *
 * @property label Single-character day-of-week label, e.g. `"W"`.
 * @property totalSeconds Seconds listened on that day.
 * @property isToday Whether this column is today — the accented bar.
 */
data class WeekChartColumn(
    val label: String,
    val totalSeconds: Long,
    val isToday: Boolean,
)

/**
 * One bar of the Home "top genres" breakdown.
 *
 * @property genreName The genre's display name.
 * @property percent Whole-number share, 0..100.
 */
data class GenreShareBar(
    val genreName: String,
    val percent: Int,
)

/**
 * Projects [DayBucket]s into left-to-right chart columns.
 *
 * Shared rather than reimplemented per platform, because three things here are easy to get
 * backwards and impossible to notice once wrong — every bar still renders, just against the wrong
 * day:
 *
 * 1. Buckets arrive **today-first** (`dayOffsetFromToday == 0` is index 0), but a week chart reads
 *    oldest-to-newest, so the list is reversed for display and **today is the rightmost column**.
 * 2. The label comes from the real calendar date, not the index — `today.minus(offset, DAY)` — so
 *    a gap in the buckets cannot silently shift every label by a day.
 * 3. `isToday` is decided by the bucket's own offset rather than by its position in the output,
 *    so it stays correct even if a caller passes fewer than seven buckets.
 *
 * [today] is a parameter rather than read from a clock in here so the projection stays pure and
 * specs can pin a known week.
 */
fun weekChartColumns(
    dailyBuckets: List<DayBucket>,
    today: LocalDate,
): List<WeekChartColumn> =
    dailyBuckets.reversed().map { bucket ->
        WeekChartColumn(
            label = today.minus(bucket.dayOffsetFromToday, DateTimeUnit.DAY).dayOfWeek.narrowLabel(),
            totalSeconds = bucket.totalSeconds,
            isToday = bucket.dayOffsetFromToday == 0,
        )
    }

/**
 * [weekChartColumns] against today in the device's own timezone.
 *
 * The clock read lives here rather than at each call site so no client has to import date
 * plumbing to draw the chart, and so "what counts as today" cannot drift between platforms.
 */
fun weekChartColumns(dailyBuckets: List<DayBucket>): List<WeekChartColumn> =
    weekChartColumns(
        dailyBuckets,
        Clock.System
            .now()
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .date,
    )

/**
 * Projects [GenreShare]s into percentage bars.
 *
 * The percentages are a share of the **genres shown**, not of all listening — so the bars sum to
 * roughly 100% and read as a breakdown of the top three rather than as three small slices of an
 * invisible whole. That is what the design asks for, and it is the behaviour the Compose clients
 * already ship; it is stated here because the alternative reading is just as plausible.
 *
 * An all-zero input yields all-zero bars rather than dividing by zero.
 */
fun genreShareBars(genres: List<GenreShare>): List<GenreShareBar> {
    val total = genres.sumOf { it.totalSeconds }
    if (total <= 0L) return genres.map { GenreShareBar(it.genreName, percent = 0) }
    return genres.map { genre ->
        GenreShareBar(
            genreName = genre.genreName,
            percent = (genre.totalSeconds.toDouble() / total * PERCENT_SCALE).toInt().coerceIn(0, PERCENT_SCALE),
        )
    }
}

private const val PERCENT_SCALE = 100

/** Single-character day-of-week label. Saturday and Sunday deliberately share `"S"`, as Compose does. */
private fun DayOfWeek.narrowLabel(): String =
    when (this) {
        DayOfWeek.MONDAY -> "M"
        DayOfWeek.TUESDAY -> "T"
        DayOfWeek.WEDNESDAY -> "W"
        DayOfWeek.THURSDAY -> "T"
        DayOfWeek.FRIDAY -> "F"
        DayOfWeek.SATURDAY -> "S"
        DayOfWeek.SUNDAY -> "S"
    }
