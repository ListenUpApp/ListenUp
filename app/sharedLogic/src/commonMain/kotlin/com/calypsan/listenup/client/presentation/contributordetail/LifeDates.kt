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
    val day = LocalDate.Formats.ISO.parseOrNull(isoDate.trim()) ?: return null
    val midday = day.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds() + 12.hours.inWholeMilliseconds
    return formatDateLong(midday)
}
