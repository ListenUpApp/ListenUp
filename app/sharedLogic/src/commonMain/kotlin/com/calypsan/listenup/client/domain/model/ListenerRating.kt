package com.calypsan.listenup.client.domain.model

/**
 * One listener's rating of a book, as the UI reads it.
 *
 * @property halfStars 2..10 half-star units (7 is 3½ stars).
 */
data class ListenerRating(
    val bookId: String,
    val userId: String,
    val halfStars: Int,
    val note: String?,
    val ratedAtMs: Long,
)

/**
 * Your listeners' average for one book: [averageHalfStars] on the same 2..10 scale, over [count]
 * ratings. A book nobody has rated has no average at all — never a zero.
 */
data class ListenerAverage(
    val averageHalfStars: Double,
    val count: Int,
)
