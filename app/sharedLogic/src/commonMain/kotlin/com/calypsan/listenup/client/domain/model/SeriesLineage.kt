package com.calypsan.listenup.client.domain.model

/**
 * Where a series sits in the hierarchy, and what its sub-series hold.
 *
 * @property ancestors the series above this one, root first — the breadcrumb.
 * @property children the direct sub-series, in sibling order.
 * @property subtreeBooks every book in this series and its sub-series, in series order
 *   (sub-series first, in sibling order, then the series' own books). Empty when the series has
 *   no sub-series: a flat series' books come from `observeSeriesWithBooks` exactly as before.
 * @property ownBookIds the books of [subtreeBooks] listed under this series itself — its own books
 *   that no sub-series reached first. Empty when the series has no sub-series.
 */
data class SeriesLineage(
    val ancestors: List<Series>,
    val children: List<SeriesChild>,
    val subtreeBooks: List<BookListItem>,
    val ownBookIds: List<String> = emptyList(),
) {
    companion object {
        /** A series with no parent and no sub-series. */
        val Flat = SeriesLineage(ancestors = emptyList(), children = emptyList(), subtreeBooks = emptyList())
    }
}

/**
 * One sub-series of a parent, with its own sub-series nested below it.
 *
 * @property bookIds the ids of every book in this sub-series and its own sub-series, in series order.
 * @property ownBookIds the books listed under this sub-series itself: its own, that none of its
 *   sub-series (or an earlier branch) reached first.
 * @property children this sub-series' own sub-series, in sibling order.
 */
data class SeriesChild(
    val series: Series,
    val bookIds: List<String>,
    val ownBookIds: List<String> = emptyList(),
    val children: List<SeriesChild> = emptyList(),
)
