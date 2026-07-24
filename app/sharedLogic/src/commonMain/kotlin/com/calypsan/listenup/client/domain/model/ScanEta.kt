package com.calypsan.listenup.client.domain.model

import kotlin.math.ceil
import kotlin.math.max

private const val MIN_FRACTION_TO_ESTIMATE = 0.02f
private const val MS_PER_MINUTE = 60_000.0

/**
 * Estimates minutes remaining from [elapsedMs] and completion [fraction] (0..1), assuming a
 * roughly constant rate. Returns null until [fraction] exceeds a small threshold (too jumpy to
 * show before then). Floored at 1 minute so a near-done scan never reads "0 min left".
 */
fun etaMinutes(
    elapsedMs: Long,
    fraction: Float,
): Int? {
    if (fraction <= MIN_FRACTION_TO_ESTIMATE) return null
    val totalMs = elapsedMs / fraction
    val remainingMs = totalMs * (1f - fraction)
    return max(1, ceil(remainingMs / MS_PER_MINUTE).toInt())
}
