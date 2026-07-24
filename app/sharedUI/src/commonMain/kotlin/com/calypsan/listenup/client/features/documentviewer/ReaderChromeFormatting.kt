package com.calypsan.listenup.client.features.documentviewer

import kotlin.math.roundToInt

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
