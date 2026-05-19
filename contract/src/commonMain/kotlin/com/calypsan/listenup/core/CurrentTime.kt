@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.core

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Get current epoch milliseconds.
 */
fun currentEpochMilliseconds(): Long = Clock.System.now().toEpochMilliseconds()

/**
 * Get current hour of day (0-23) in the user's local timezone.
 * Used for time-aware greetings.
 */
fun currentHourOfDay(): Int {
    val now = Clock.System.now()
    val tz = TimeZone.currentSystemDefault()
    return now.toLocalDateTime(tz).hour
}
