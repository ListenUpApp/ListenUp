package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.Series
import com.calypsan.listenup.client.domain.model.SeriesChild
import com.calypsan.listenup.client.domain.model.SeriesLineage
import com.calypsan.listenup.domain.series.SeriesMembership
import com.calypsan.listenup.domain.series.SeriesNode
import com.calypsan.listenup.domain.series.SeriesTree

/**
 * Turns the flat list of live series plus a subtree's books into a [SeriesLineage]. Pure: the
 * repository supplies the Room rows, this decides the shape, and [SeriesTree] — shared with the
 * server — decides the order. Books that tie on sequence order by title, as a flat series' do.
 */
internal class SeriesLineageResolver(
    allSeries: List<Series>,
) {
    private val byId = allSeries.associateBy { it.id.value }
    private val tree = SeriesTree(allSeries.map { SeriesNode(it.id.value, it.parentId?.value, it.parentPosition) })

    /** [seriesId] and every sub-series below it — the series ids the book query must cover. */
    fun subtreeOf(seriesId: String): Set<String> = tree.subtreeOf(seriesId)

    /** Whether [seriesId] has at least one sub-series. */
    fun hasSubSeries(seriesId: String): Boolean = tree.childrenOf(seriesId).isNotEmpty()

    /** The lineage of [seriesId], given the books of its subtree (ignored for a flat series). */
    fun resolve(
        seriesId: String,
        subtreeBooks: List<BookListItem>,
    ): SeriesLineage {
        val ancestors = tree.ancestorsOf(seriesId).mapNotNull(byId::get)
        val childIds = tree.childrenOf(seriesId)
        if (childIds.isEmpty()) return SeriesLineage(ancestors, emptyList(), emptyList())

        val subtree = tree.subtreeOf(seriesId)
        val memberships =
            subtreeBooks.flatMap { book ->
                book.series
                    .filter { it.seriesId in subtree }
                    .map { SeriesMembership(book.id.value, it.seriesId, it.sequence, sortKey = book.title) }
            }
        val bookById = subtreeBooks.associateBy { it.id.value }
        return SeriesLineage(
            ancestors = ancestors,
            children =
                childIds.mapNotNull { childId ->
                    byId[childId]?.let { SeriesChild(it, tree.defaultBookOrder(childId, memberships)) }
                },
            subtreeBooks = tree.defaultBookOrder(seriesId, memberships).mapNotNull(bookById::get),
        )
    }
}
