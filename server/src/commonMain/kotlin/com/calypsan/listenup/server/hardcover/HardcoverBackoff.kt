package com.calypsan.listenup.server.hardcover

import kotlin.time.Duration

private const val MAX_DOUBLINGS = 20

/** [base] doubled for each attempt after the first, never more than [cap]: 1 → base, 2 → 2×base, … */
internal fun exponentialBackoff(
    attempt: Int,
    base: Duration,
    cap: Duration,
): Duration = minOf(base * (1 shl (attempt - 1).coerceIn(0, MAX_DOUBLINGS)), cap)
