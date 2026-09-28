package com.calypsan.listenup.server.metadata.spi

/**
 * A catalog's rating of one book, as [RatingSource] reports it: [average] 0–5 over [count]
 * ratings.
 */
data class ExternalRatingMeta(
    val average: Double,
    val count: Int,
)
