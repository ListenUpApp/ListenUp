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
}
