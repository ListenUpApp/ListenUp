package com.calypsan.listenup.client.presentation.bookdetail

import com.calypsan.listenup.client.domain.chapter.ChapterOutlineRow
import com.calypsan.listenup.client.domain.chapter.toChapterOutline
import com.calypsan.listenup.client.domain.model.Chapter

/**
 * A marked book's chapters as Book Detail draws them. Only a book with markers has one: a flat book
 * has none, and every surface draws its plain list exactly as it always has.
 *
 * Rendering a [ChapterOutlineRow.ChapterRow] needs nothing but its number: [ChapterOutlineRow.ChapterRow.number]
 * less one indexes [BookDetailUiState.Ready.chapters].
 *
 * @property rows the whole outline, announcement chapters folded, for "All N chapters".
 * @property previewLabel the part (or book) the collapsed list starts inside, whose own header is out
 *   of the window; null when the list opens on a header or outside any group.
 * @property previewRows the collapsed list: the same window as
 *   [BookDetailUiState.Ready.chapterPreview], through the outline, so a header opening inside it shows.
 */
data class GroupedChapters(
    val rows: List<ChapterOutlineRow>,
    val previewLabel: String?,
    val previewRows: List<ChapterOutlineRow>,
)

/**
 * This book's [GroupedChapters], or null when it has no markers.
 *
 * @param currentIndex the current chapter's index in the receiver; null when there is none.
 */
fun List<Chapter>.groupedChapters(currentIndex: Int?): GroupedChapters? {
    val rows = toChapterOutline(currentChapterId = currentIndex?.let { getOrNull(it)?.id })
    if (rows.all { it is ChapterOutlineRow.ChapterRow }) return null

    val window = chapterWindow(this, currentIndex).mapTo(mutableSetOf()) { it.id }
    val firstInWindow = rows.indexOfFirst { it.openingChapterId in window }
    return GroupedChapters(
        rows = rows,
        previewLabel = rows.labelFor(firstInWindow),
        previewRows = rows.filter { it.openingChapterId in window },
    )
}

/** The chapter this row stands for: its own, or for a header the one that opens the group. */
private val ChapterOutlineRow.openingChapterId: String
    get() =
        when (this) {
            is ChapterOutlineRow.BookHeader -> chapterId
            is ChapterOutlineRow.PartHeader -> chapterId
            is ChapterOutlineRow.ChapterRow -> chapter.id
        }

/** The name of the group the row at [index] sits inside; null when that row is a header or sits in none. */
private fun List<ChapterOutlineRow>.labelFor(index: Int): String? {
    val row = getOrNull(index) as? ChapterOutlineRow.ChapterRow ?: return null
    val before = take(index)
    return before.filterIsInstance<ChapterOutlineRow.PartHeader>().lastOrNull { it.chapterId == row.partId }?.title
        ?: before.filterIsInstance<ChapterOutlineRow.BookHeader>().lastOrNull()?.title.takeIf { row.partId == null }
}

/** The part [chapterId] sits in, for the resume line: null for a chapter folded away, whose title is the part's. */
internal fun List<ChapterOutlineRow>.partTitleOf(chapterId: String): String? {
    val partId = filterIsInstance<ChapterOutlineRow.ChapterRow>().firstOrNull { it.chapter.id == chapterId }?.partId
    return filterIsInstance<ChapterOutlineRow.PartHeader>().firstOrNull { it.chapterId == partId }?.title
}
