package com.calypsan.listenup.client.presentation.seriesdetail

import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.SeriesChild
import com.calypsan.listenup.client.domain.model.SeriesLineage
import com.calypsan.listenup.core.BookId

/** What a [SeriesBookSection] heads. */
enum class SeriesSectionKind {
    /** A sub-series: its heading links to its page, and it can be collapsed. */
    SUB_SERIES,

    /**
     * A series' own books, listed after its sub-series — "Also in Cosmere". On a page with no
     * sub-series it is the only section, and the screen shows it without a heading.
     */
    OWN_BOOKS,
}

/**
 * One heading of a series page's book list, with the books listed under it.
 *
 * Sections come in reading order, nested by [depth]: a sub-series heading (depth 1) is followed by
 * its own sub-series (depth 2…) and then its own books. A collapsed section's books are not listed,
 * and its nested sections are left out of the list entirely.
 *
 * @property key stable across emissions, for list diffing.
 * @property seriesId the series this section's books belong to.
 * @property title that series' name. The screen reads [SeriesSectionKind.OWN_BOOKS] as "Also in {title}".
 * @property path the names from the page's direct sub-series down to this one — "Mistborn",
 *   "Mistborn Era 1" — for a heading that names its place.
 * @property depth 1 for a heading directly under the page; deeper for nested sub-series.
 * @property books the books listed under this heading — empty for a sub-series whose books all sit in
 *   its own sub-series, and for a collapsed section.
 * @property bookCount every book under this heading, its nested sub-series included.
 * @property finishedCount how many of [bookCount] the reader has finished.
 * @property isCollapsed true when the section is folded — a fully finished sub-series starts this way.
 */
data class SeriesBookSection(
    val key: String,
    val seriesId: String,
    val title: String,
    val kind: SeriesSectionKind,
    val path: List<String>,
    val depth: Int,
    val books: List<BookListItem>,
    val bookCount: Int,
    val finishedCount: Int,
    val isCollapsed: Boolean,
) {
    /** Whether the reader can fold this section: only sub-series fold. */
    val isCollapsible: Boolean get() = kind == SeriesSectionKind.SUB_SERIES
}

/**
 * The sections of a series page. A page with sub-series groups its books under each sub-series, in
 * tree order, and lists its own books last; a flat page is one [SeriesSectionKind.OWN_BOOKS] section
 * holding [flatBooks].
 *
 * A sub-series whose books are all finished starts collapsed. [expandOverrides] holds the reader's own
 * choices — series id to expanded — and beats that default both ways.
 */
internal fun seriesBookSections(
    pageId: String,
    pageName: String,
    lineage: SeriesLineage,
    flatBooks: List<BookListItem>,
    booksById: Map<String, BookListItem>,
    finishedBookIds: Set<BookId>,
    expandOverrides: Map<String, Boolean>,
): List<SeriesBookSection> {
    if (lineage.children.isEmpty()) {
        return listOf(
            ownBooksSection(pageId, pageName, path = emptyList(), depth = 1, flatBooks, finishedBookIds),
        )
    }

    fun books(ids: List<String>) = ids.mapNotNull(booksById::get)

    val sections = mutableListOf<SeriesBookSection>()

    fun addBranch(
        child: SeriesChild,
        parentPath: List<String>,
        depth: Int,
    ) {
        val id = child.series.id.value
        val path = parentPath + child.series.name
        val finished = child.bookIds.count { BookId(it) in finishedBookIds }
        val isFinished = child.bookIds.isNotEmpty() && finished == child.bookIds.size
        val collapsed = !(expandOverrides[id] ?: !isFinished)
        // A leaf lists its books under its own heading; a branch lists them after its sub-series.
        val isLeaf = child.children.isEmpty()
        sections +=
            SeriesBookSection(
                key = "sub:$id",
                seriesId = id,
                title = child.series.name,
                kind = SeriesSectionKind.SUB_SERIES,
                path = path,
                depth = depth,
                books = if (isLeaf && !collapsed) books(child.ownBookIds) else emptyList(),
                bookCount = child.bookIds.size,
                finishedCount = finished,
                isCollapsed = collapsed,
            )
        if (collapsed || isLeaf) return
        child.children.forEach { addBranch(it, path, depth + 1) }
        if (child.ownBookIds.isNotEmpty()) {
            sections +=
                ownBooksSection(id, child.series.name, path, depth + 1, books(child.ownBookIds), finishedBookIds)
        }
    }

    lineage.children.forEach { addBranch(it, parentPath = emptyList(), depth = 1) }
    if (lineage.ownBookIds.isNotEmpty()) {
        sections +=
            ownBooksSection(pageId, pageName, emptyList(), depth = 1, books(lineage.ownBookIds), finishedBookIds)
    }
    return sections
}

private fun ownBooksSection(
    seriesId: String,
    seriesName: String,
    path: List<String>,
    depth: Int,
    books: List<BookListItem>,
    finishedBookIds: Set<BookId>,
) = SeriesBookSection(
    key = "own:$seriesId",
    seriesId = seriesId,
    title = seriesName,
    kind = SeriesSectionKind.OWN_BOOKS,
    path = path,
    depth = depth,
    books = books,
    bookCount = books.size,
    finishedCount = books.count { it.id in finishedBookIds },
    isCollapsed = false,
)
