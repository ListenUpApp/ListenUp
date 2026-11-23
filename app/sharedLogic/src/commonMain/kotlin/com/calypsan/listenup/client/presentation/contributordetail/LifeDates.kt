package com.calypsan.listenup.client.presentation.contributordetail

import com.calypsan.listenup.client.util.formatDateLong
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlin.time.Duration.Companion.hours

/**
 * The line under a contributor's name: "September 21, 1947 – January 15, 2024", "Born …" or
 * "Died …", or null when neither date is a full ISO day (`yyyy-MM-dd`).
 *
 * One function for the Compose screen and the web page, so the two cannot disagree about what a
 * person's dates say.
 */
fun formatLifeDates(
    birthDate: String?,
    deathDate: String?,
): String? {
    val birth = birthDate?.let(::isoDayForDisplay)
    val death = deathDate?.let(::isoDayForDisplay)

    return when {
        birth != null && death != null -> "$birth – $death"
        birth != null -> "Born $birth"
        death != null -> "Died $death"
        else -> null
    }
}

/**
 * An ISO day as a long localized date, or null when it is not one.
 *
 * Formatted from midday UTC, not midnight: the platform formatters render in the device's zone,
 * and midnight UTC is the previous evening anywhere west of Greenwich — a birthday a day early.
 */
private fun isoDayForDisplay(isoDate: String): String? {
    val day = parseDay(isoDate) ?: return null
    val midday = day.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds() + 12.hours.inWholeMilliseconds
    return formatDateLong(midday)
}

/**
 * Year, month and day separated by `-`, each part any number: "1947-09-21", "1947-9-21" and
 * "+1947-09-21" are the same day. The stored string is whatever was written, unvalidated, and this
 * is what the Android screen accepted before the formatter was shared, so no date it showed is lost.
 * A day the calendar lacks ("1947-02-30") is null.
 */
private fun parseDay(raw: String): LocalDate? {
    val parts = raw.trim().split("-")
    if (parts.size != DATE_PARTS) return null
    val (year, month, day) = parts.map { it.toIntOrNull() ?: return null }
    if (year < 0) return null
    val padded = "${year.pad(YEAR_DIGITS)}-${month.pad(2)}-${day.pad(2)}"
    return LocalDate.Formats.ISO.parseOrNull(padded)
}

private fun Int.pad(digits: Int): String = toString().padStart(digits, '0')

private const val DATE_PARTS = 3
private const val YEAR_DIGITS = 4
