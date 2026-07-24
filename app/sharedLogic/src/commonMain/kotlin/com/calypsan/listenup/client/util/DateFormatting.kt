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

/**
 * A human "last active" display for a device/session timestamp, relative to [nowMs]:
 * - under 1 minute → "Just now"
 * - under 1 hour → "N minute(s) ago"
 * - under 1 day → "N hour(s) ago"
 * - 1–2 days → "Yesterday"
 * - under a week → "N days ago"
 * - under 30 days → "N week(s) ago"
 * - else → "Month Year"
 *
 * Pure (takes [nowMs]) so it is unit-testable without time injection. Clamps
 * negative deltas (clock skew / server-ahead) to "Just now".
 */
fun relativeLastActive(
    lastUsedAtMs: Long,
    nowMs: Long,
): String {
    val deltaMs = (nowMs - lastUsedAtMs).coerceAtLeast(0)
    val minutes = deltaMs / MILLIS_PER_MINUTE
    val hours = deltaMs / MILLIS_PER_HOUR
    val days = (deltaMs / MILLIS_PER_DAY).toInt()
    return when {
        minutes < 1 -> {
            "Just now"
        }

        minutes < MINUTES_PER_HOUR -> {
            if (minutes == 1L) "1 minute ago" else "$minutes minutes ago"
        }

        hours < HOURS_PER_DAY -> {
            if (hours == 1L) "1 hour ago" else "$hours hours ago"
        }

        days < 2 -> {
            "Yesterday"
        }

        days < DAYS_PER_WEEK -> {
            "$days days ago"
        }

        days < MONTH_YEAR_THRESHOLD_DAYS -> {
            val weeks = days / DAYS_PER_WEEK
            if (weeks == 1) "1 week ago" else "$weeks weeks ago"
        }

        else -> {
            formatDate(lastUsedAtMs, "MMMM yyyy")
        }
    }
}

private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
private const val MILLIS_PER_MINUTE = 60L * 1000
private const val MILLIS_PER_HOUR = 60L * 60 * 1000
private const val MINUTES_PER_HOUR = 60L
private const val HOURS_PER_DAY = 24L
private const val DAYS_PER_WEEK = 7
private const val MONTH_YEAR_THRESHOLD_DAYS = 30
