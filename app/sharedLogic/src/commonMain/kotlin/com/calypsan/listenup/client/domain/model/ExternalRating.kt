package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.api.sync.ExternalRatingSource

/** One outside catalog's rating of a book, as the UI reads it. */
data class ExternalRating(
    val source: ExternalRatingSource,
    val average: Double,
    val count: Int,
)

/**
 * The ListenUp score of one book — see [listenUpScore].
 *
 * @property average the score, on the familiar 1–5 scale.
 * @property count every rating behind it, outside catalogs and this server's listeners together.
 * @property shares how much of the score each contributing source carries, summing to 1. A source
 *   with no ratings is absent, so the keys are exactly the sources the score was combined from.
 */
data class CombinedScore(
    val average: Double,
    val count: Int,
    val shares: Map<ScoreSource, Double> = emptyMap(),
) {
    /** How many sources the score was combined from ("Combined from N sources"). */
    val sourceCount: Int get() = shares.size

    /**
     * Whether this server's listeners are the score's only source. Book Detail then shows no
     * headline: the listeners' own line already says what they think, and a second number read
     * off ListenUp's curve (a lone 5★ scores about 4.4) would look like a contradiction.
     */
    val isListenersOnly: Boolean get() = shares.keys == setOf(ScoreSource.Listeners)
}
