package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.domain.averageLabel as domainAverageLabel
import com.calypsan.listenup.domain.compactCount as domainCompactCount

/**
 * Wraps `:contract`'s [domainAverageLabel] and [domainCompactCount] (`RatingCounts.kt`) so Swift
 * Export reaches them. Swift Export only ever emits a `:contract` symbol that something in
 * `:app:sharedLogic`'s own public API surface uses — a bare top-level function nobody calls from
 * here is tree-shaken away before it reaches `Shared.swift`. Exposed the same way PR 1 exposed
 * [com.calypsan.listenup.domain.ListenerRatingLimits] through `BookRatingsViewModel.limits`.
 */
object RatingLabels {
    /** [average] (0-5) as every platform shows it beside a star: one decimal, half-up. */
    fun averageLabel(average: Double): String = domainAverageLabel(average)

    /** [count] as every platform shows it next to a score: "812", "1.2k", "12k", "1.2M". */
    fun compactCount(count: Int): String = domainCompactCount(count)

    /** Your listeners' [average] as every platform shows it: one decimal, like the score ("4.0", never "4"). */
    fun listenerAverageLabel(average: ListenerAverage): String = domainAverageLabel(average.averageHalfStars / 2)

    /**
     * Whole days from [fetchedAtMs] to [nowMs], for "Updated 3 days ago": 0 is today, 1 yesterday. A fetch
     * time ahead of the device's clock counts as today rather than as a negative age.
     */
    fun daysSince(
        fetchedAtMs: Long,
        nowMs: Long,
    ): Int = ((nowMs - fetchedAtMs).coerceAtLeast(0L) / MILLIS_PER_DAY).toInt()
}

private const val MILLIS_PER_DAY = 86_400_000L
