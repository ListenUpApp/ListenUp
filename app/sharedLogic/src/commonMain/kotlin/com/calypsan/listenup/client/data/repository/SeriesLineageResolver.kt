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
        // One walk decides where every book is listed; the nested children read it, so a heading can
        // never disagree with the order the books are listed in.
        val groups = tree.defaultBookGroups(seriesId, memberships)

        fun child(childId: String): SeriesChild? =
            byId[childId]?.let { series ->
                SeriesChild(
                    series = series,
                    // Every book of the child's subtree, in the order its own page lists them.
                    bookIds = tree.defaultBookOrder(childId, memberships),
                    ownBookIds = groups[childId].orEmpty(),
                    children = tree.childrenOf(childId).mapNotNull(::child),
                )
            }

        return SeriesLineage(
            ancestors = ancestors,
            children = childIds.mapNotNull(::child),
            subtreeBooks = groups.values.flatten().mapNotNull(bookById::get),
            ownBookIds = groups[seriesId].orEmpty(),
        )
    }
}
