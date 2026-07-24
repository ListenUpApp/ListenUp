@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.client.util

import com.calypsan.listenup.core.Timestamp
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

private const val DAYS_IN_WEEK = 7
private const val DAYS_IN_MONTH = 30
private const val DAYS_IN_QUARTER = 90

/**
 * Convert an ISO 8601 timestamp to a human-readable relative time or "Month Year" format.
 *
 * Logic:
 * - Less than 7 days: "this week"
 * - Less than 30 days: "X weeks ago"
 * - Less than 90 days: "X months ago"
 * - 90+ days: "Month Year" (e.g., "April 2022")
 *
 * @receiver ISO 8601 timestamp string
 * @return Human-readable time string
 */
fun String.toRelativeOrMonthYear(): String {
    val instant = Instant.parse(this)
    val now = Clock.System.now()
    val diffMillis = now.toEpochMilliseconds() - instant.toEpochMilliseconds()
    val diffDays = diffMillis / (1000 * 60 * 60 * 24)

    return when {
        diffDays < DAYS_IN_WEEK -> {
            "this week"
        }

        diffDays < DAYS_IN_MONTH -> {
            val weeks = (diffDays / DAYS_IN_WEEK).toInt()
            "$weeks ${if (weeks == 1) "week" else "weeks"} ago"
        }

        diffDays < DAYS_IN_QUARTER -> {
            val months = (diffDays / DAYS_IN_MONTH).toInt()
            "$months ${if (months == 1) "month" else "months"} ago"
        }

        else -> {
            val localDate = instant.toLocalDateTime(TimeZone.currentSystemDefault())
            val monthName =
                when (localDate.month.name.lowercase()) {
                    "january" -> {
                        "January"
                    }

                    "february" -> {
                        "February"
                    }

                    "march" -> {
                        "March"
                    }

                    "april" -> {
                        "April"
                    }

                    "may" -> {
                        "May"
                    }

                    "june" -> {
                        "June"
                    }

                    "july" -> {
                        "July"
                    }

                    "august" -> {
                        "August"
                    }

                    "september" -> {
                        "September"
                    }

                    "october" -> {
                        "October"
                    }

                    "november" -> {
                        "November"
                    }

                    "december" -> {
                        "December"
                    }

                    else -> {
                        localDate.month.name
                            .lowercase()
                            .replaceFirstChar { it.uppercase() }
                    }
                }
            "$monthName ${localDate.year}"
        }
    }
}

/**
 * Parse an ISO 8601 timestamp string to [Timestamp], falling back to current time on failure.
 *
 * Use this for server responses where parsing failure should not crash the app,
 * but instead gracefully degrade to current time.
 *
 * @receiver ISO 8601 timestamp string (e.g., "2023-11-22T14:30:45.123Z")
 * @return Parsed [Timestamp] or [Timestamp.now] if parsing fails
 */
fun String.parseToTimestampOrNow(): Timestamp =
    try {
        Timestamp(Instant.parse(this).toEpochMilliseconds())
    } catch (_: Exception) {
        Timestamp.now()
    }
