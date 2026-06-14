package com.calypsan.listenup.client.util

/**
 * Format epoch milliseconds to a localized date string.
 *
 * Platform implementations use native formatters for proper localization.
 *
 * @param epochMillis Unix timestamp in milliseconds
 * @param pattern Date format pattern (e.g., "MMMM d, yyyy" for "January 15, 2024")
 * @return Formatted date string
 */
expect fun formatDate(
    epochMillis: Long,
    pattern: String,
): String

/**
 * Format epoch milliseconds to a short date (e.g., "Jan 15, 2024").
 */
fun formatDateShort(epochMillis: Long): String = formatDate(epochMillis, "MMM d, yyyy")

/**
 * Format epoch milliseconds to a long date (e.g., "January 15, 2024").
 */
fun formatDateLong(epochMillis: Long): String = formatDate(epochMillis, "MMMM d, yyyy")

/**
 * A human display of a finish time relative to [nowMs]:
 * - under 1 day → "today"
 * - 1–6 days → "yesterday" or "N days ago"
 * - 7–29 days → "N weeks ago" (rounded down to nearest week)
 * - 30+ days → "Month Year" (e.g. "April 2026")
 *
 * Pure (takes [nowMs]) so it is unit-testable without time injection.
 */
fun relativeOrMonthYear(
    finishedAtMs: Long,
    nowMs: Long,
): String {
    val deltaMs = nowMs - finishedAtMs
    val deltaDays = (deltaMs / MILLIS_PER_DAY).toInt()
    return when {
        deltaDays < 1 -> {
            "today"
        }

        deltaDays < 2 -> {
            "yesterday"
        }

        deltaDays < DAYS_PER_WEEK -> {
            "$deltaDays days ago"
        }

        deltaDays < MONTH_YEAR_THRESHOLD_DAYS -> {
            val weeks = deltaDays / DAYS_PER_WEEK
            if (weeks == 1) "1 week ago" else "$weeks weeks ago"
        }

        else -> {
            formatDate(finishedAtMs, "MMMM yyyy")
        }
    }
}

private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
private const val DAYS_PER_WEEK = 7
private const val MONTH_YEAR_THRESHOLD_DAYS = 30
