package com.calypsan.listenup.client.domain.model

/**
 * Per-series listening progress, aggregated from per-book finished state.
 *
 * Drives the series-list affordance: [isComplete] → a "Complete" pill,
 * [isNotStarted] → "Not started", otherwise an "X of Y" bar of [fraction].
 */
data class SeriesProgress(
    val finishedCount: Int,
    val totalCount: Int,
) {
    /** True when every book in the series is finished (and the series is non-empty). */
    val isComplete: Boolean get() = totalCount > 0 && finishedCount >= totalCount

    /** True when no book has been finished yet. */
    val isNotStarted: Boolean get() = finishedCount == 0

    /** Finished fraction (0..1) for the linear bar; 0 when the series is empty. */
    val fraction: Float get() = if (totalCount > 0) finishedCount.toFloat() / totalCount else 0f
}
