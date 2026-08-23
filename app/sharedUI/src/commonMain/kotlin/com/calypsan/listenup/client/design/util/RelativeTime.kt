@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.client.design.util

import androidx.compose.runtime.Composable
import kotlin.time.Clock
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.discover_time_ago_days
import listenup.composeapp.generated.resources.discover_time_ago_hours
import listenup.composeapp.generated.resources.discover_time_ago_minutes
import listenup.composeapp.generated.resources.discover_time_ago_now
import org.jetbrains.compose.resources.stringResource

/** Milliseconds in a minute — the coarsest unit [relativeTime] resolves. */
private const val MS_PER_MINUTE = 60_000L

/** Minutes in an hour, for the minutes → hours rollover. */
private const val MINUTES_PER_HOUR = 60L

/** Hours in a day, for the hours → days rollover. */
private const val HOURS_PER_DAY = 24L

/**
 * Compact relative timestamp ("Just now", "5m ago", "3h ago", "2d ago") for the epoch-ms instant
 * [occurredAtMs], measured against the system clock at composition time. Instants in the future
 * clamp to "Just now" rather than reading negative.
 *
 * This is the single relative-time phrasing for the app: the Discover activity feed, the admin
 * password-reset queue, and the notification inbox all call it, so the same age never reads two
 * different ways on two surfaces. iOS phrases its own timestamps with `RelativeDateTimeFormatter`;
 * the buckets here are chosen to line up with it.
 *
 * The `discover_time_ago_*` string keys are historical — they date from the activity feed, which
 * was the first consumer — and are deliberately left alone rather than renamed for tidiness.
 */
@Composable
fun relativeTime(occurredAtMs: Long): String {
    val nowMs = Clock.System.now().toEpochMilliseconds()
    val minutes = (nowMs - occurredAtMs).coerceAtLeast(0L) / MS_PER_MINUTE
    return when {
        minutes < 1L -> {
            stringResource(Res.string.discover_time_ago_now)
        }

        minutes < MINUTES_PER_HOUR -> {
            stringResource(Res.string.discover_time_ago_minutes, minutes.toInt())
        }

        minutes < MINUTES_PER_HOUR * HOURS_PER_DAY -> {
            stringResource(Res.string.discover_time_ago_hours, (minutes / MINUTES_PER_HOUR).toInt())
        }

        else -> {
            stringResource(
                Res.string.discover_time_ago_days,
                (minutes / (MINUTES_PER_HOUR * HOURS_PER_DAY)).toInt(),
            )
        }
    }
}
