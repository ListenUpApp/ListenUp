package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.api.sync.ExternalRatingSource

/** One outside catalog's rating of a book, as the UI reads it. */
data class ExternalRating(
    val source: ExternalRatingSource,
    val average: Double,
    val count: Int,
)

/** The headline: every enabled source's average, weighted by how many ratings each is over. */
data class CombinedScore(
    val average: Double,
    val count: Int,
)

/**
 * Σ(average·count) / Σcount over [ratings]; null when there are none or the total count is 0.
 * Callers pass only enabled sources — [com.calypsan.listenup.client.domain.repository.BookRatingRepository]
 * filters disabled and `UNKNOWN`-source rows out before this ever sees them.
 */
fun combineExternalRatings(ratings: List<ExternalRating>): CombinedScore? {
    val total = ratings.sumOf { it.count.toLong() }
    if (total == 0L) return null
    val weighted = ratings.sumOf { it.average * it.count }
    return CombinedScore(average = weighted / total, count = total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
}
