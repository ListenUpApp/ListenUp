package com.calypsan.listenup.client.presentation.storyworld

/**
 * Identifies a Story World scope to observe — either a series or a standalone book.
 *
 * Mirrors the dual-home rule shared by [com.calypsan.listenup.client.domain.model.Entity] and
 * [com.calypsan.listenup.client.domain.model.WorldEvent]: exactly one of [seriesId] / [bookId]
 * is non-null, never both, never neither.
 *
 * @property seriesId The series to observe, or null when this ref identifies a standalone book.
 * @property bookId The standalone book to observe, or null when this ref identifies a series.
 */
data class WorldRef(
    val seriesId: String? = null,
    val bookId: String? = null,
) {
    init {
        val hasSeries = seriesId != null
        val hasBook = bookId != null
        require(hasSeries != hasBook) {
            "exactly one of seriesId/bookId must be set"
        }
    }
}
