package com.calypsan.listenup.domain

import kotlin.math.floor

/** Below this, [compactCount] says the exact number — no scaling needed. */
private const val THOUSAND = 1_000

/** The highest count that still rounds to "999k" rather than crossing over to "1M". */
private const val JUST_UNDER_A_MILLION = 999_500

private const val MILLION = 1_000_000

/** The offset that turns [kotlin.math.floor] into round-half-up. */
private const val HALF_UP_OFFSET = 0.5

/**
 * [count] as every platform shows it next to a score: "812", "1.2k", "12k", "1.2M". One decimal
 * below ten of a unit, none above, so the width stays short.
 */
fun compactCount(count: Int): String =
    when {
        count < THOUSAND -> "$count"
        count < JUST_UNDER_A_MILLION -> scaled(count / THOUSAND.toDouble(), "k")
        else -> scaled(count / MILLION.toDouble(), "M")
    }

private fun scaled(
    value: Double,
    suffix: String,
): String {
    // Half-up, not `kotlin.math.round`'s half-to-even: 1.25 must read "1.3", not fall back to the
    // even neighbor "1.2" — a reader compares this number to a star rating, not a banker's ledger.
    val rounded = if (value < 10) halfUp(value * 10) / 10 else halfUp(value)
    val text = if (rounded % 1.0 == 0.0) "${rounded.toLong()}" else "$rounded"
    return "$text$suffix"
}

private fun halfUp(value: Double): Double = floor(value + HALF_UP_OFFSET)
