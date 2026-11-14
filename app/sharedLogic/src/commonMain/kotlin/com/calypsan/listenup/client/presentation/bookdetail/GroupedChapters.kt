package com.calypsan.listenup.client.presentation.bookdetail

import com.calypsan.listenup.client.domain.chapter.ChapterOutlineRow
import com.calypsan.listenup.client.domain.chapter.toChapterOutline
import com.calypsan.listenup.client.domain.model.Chapter

/**
 * A marked book's chapters as Book Detail draws them. Only a book with markers has one: a flat book
 * has none, and every surface draws its plain list exactly as it always has.
 *
 * Rendering a [ChapterOutlineRow.ChapterRow] needs nothing but its [ChapterOutlineRow.ChapterRow.index],
 * which indexes [BookDetailUiState.Ready.chapters], and its [ChapterOutlineRow.ChapterRow.number], which
 * is the number to show.
 *
 * @property rows the whole outline, every chapter a row, for "All N chapters".
 * @property previewRows the collapsed list: the same five chapters as [BookDetailUiState.Ready.chapterPreview],
 *   headed by the book and part they start inside, with a header wherever the window crosses into
 *   another book or part. Headers are not chapters and never take one's place in the five.
 */
data class GroupedChapters(
    val rows: List<ChapterOutlineRow>,
    val previewRows: List<ChapterOutlineRow>,
)

/**
 * This book's [GroupedChapters], or null when it has no markers.
 *
 * @param currentIndex the current chapter's index in the receiver; null when there is none.
 */
fun List<Chapter>.groupedChapters(currentIndex: Int?): GroupedChapters? {
    val rows = toChapterOutline()
    if (rows.all { it is ChapterOutlineRow.ChapterRow }) return null

    val window = chapterWindow(this, currentIndex).mapTo(mutableSetOf()) { it.id }
    val first = rows.indexOfFirst { it is ChapterOutlineRow.ChapterRow && it.chapter.id in window }
    val last = rows.indexOfLast { it is ChapterOutlineRow.ChapterRow && it.chapter.id in window }
    return GroupedChapters(
        rows = rows,
        previewRows = rows.headersAbove(first) + rows.subList(first, last + 1),
    )
}

/**
 * The headers of the book and part the row at [index] sits inside, book first, when they come before
 * it with other rows between: the ones a window opening at [index] would otherwise leave out.
 */
private fun List<ChapterOutlineRow>.headersAbove(index: Int): List<ChapterOutlineRow> {
    val before = subList(0, index)
    val book = before.indexOfLast { it is ChapterOutlineRow.BookHeader }
    val part = before.indexOfLast { it is ChapterOutlineRow.PartHeader }.takeIf { it > book }
    return listOfNotNull(before.getOrNull(book), part?.let(before::get))
}

/** The part [chapterId] sits in, for the resume line; null outside any part. */
internal fun GroupedChapters.partTitleOf(chapterId: String): String? {
    val at = rows.indexOfFirst { it is ChapterOutlineRow.ChapterRow && it.chapter.id == chapterId }
    if (at < 0) return null
    return rows
        .subList(0, at)
        .lastOrNull { it is ChapterOutlineRow.PartHeader || it is ChapterOutlineRow.BookHeader }
        .let { it as? ChapterOutlineRow.PartHeader }
        ?.title
}
