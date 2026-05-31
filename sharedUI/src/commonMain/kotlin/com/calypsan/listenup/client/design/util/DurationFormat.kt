package com.calypsan.listenup.client.design.util

private const val MILLIS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60L

/**
 * Formats a millisecond duration as a compact `Hh Mm` / `Mm` label.
 *
 * Sub-minute durations render as `0m`; the hour segment is dropped entirely under one hour
 * (e.g. `45m`, `2h 5m`). Use this for list-item duration chips where a terse label is wanted;
 * `MetadataSection` keeps its own always-hours variant for the book-detail readout.
 */
fun formatDuration(durationMs: Long): String {
    val totalMinutes = durationMs / MILLIS_PER_MINUTE
    val hours = totalMinutes / MINUTES_PER_HOUR
    val minutes = totalMinutes % MINUTES_PER_HOUR
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}
