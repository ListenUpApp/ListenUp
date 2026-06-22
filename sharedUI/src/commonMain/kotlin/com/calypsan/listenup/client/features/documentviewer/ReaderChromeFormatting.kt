package com.calypsan.listenup.client.features.documentviewer

import kotlin.math.roundToInt

/** "9h 51m left" / "1m left" — remaining audiobook time for the reader's now-playing strip. */
internal fun formatTimeLeft(remainingMs: Long): String {
    val totalMinutes = (remainingMs / 60_000L).coerceAtLeast(0L)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes}m left" else "${minutes}m left"
}

/** 0-based page index for a scrubber fraction (0..1), clamped. `pageCount <= 0` → 0. */
internal fun scrubberPageIndex(
    fraction: Float,
    pageCount: Int,
): Int {
    if (pageCount <= 0) return 0
    val clamped = fraction.coerceIn(0f, 1f)
    val page = (clamped * pageCount).roundToInt().coerceIn(1, pageCount) // 1-based, mirrors iOS scrubberPage
    return page - 1
}
