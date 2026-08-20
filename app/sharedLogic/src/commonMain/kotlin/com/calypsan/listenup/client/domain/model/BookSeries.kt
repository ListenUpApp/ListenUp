package com.calypsan.listenup.client.domain.model

/**
 * Series membership with position within that series.
 */
data class BookSeries(
    val seriesId: String,
    val seriesName: String,
    val sequence: Double? = null, // e.g. 1.0, 1.5; null when unnumbered
)
