package com.calypsan.listenup.client.domain.chapter

import com.calypsan.listenup.client.domain.model.Chapter

/**
 * One line of a book's chapter list, in reading order.
 *
 * Flat rather than nested because every surface draws a flat list and a nested shape would make
 * each of them walk it. [toChapterOutline] is the only producer, so no platform derives grouping
 * itself. Every chapter the audiobook has is a [ChapterRow]: the headers only mark where a book or
 * part begins, and never stand in for a chapter.
 */
sealed interface ChapterOutlineRow {
    /**
     * A Book marker: the heading of one book in an omnibus.
     *
     * @property title the name given to the marker.
     * @property chapterId the chapter carrying the marker, which is the one that opens the book.
     * @property startMs where the book begins.
     */
    data class BookHeader(
        val title: String,
        val chapterId: String,
        val startMs: Long,
    ) : ChapterOutlineRow

    /**
     * A Part marker, drawn as a divider above the part's chapters. It is never current: only a
     * chapter row is.
     *
     * @property title the name given to the marker.
     * @property chapterId the chapter carrying the marker: the part's identity, and where it starts.
     * @property chapterCount every chapter row in the part.
     * @property durationMs the whole part's length.
     * @property startMs where the part begins.
     */
    data class PartHeader(
        val title: String,
        val chapterId: String,
        val chapterCount: Int,
        val durationMs: Long,
        val startMs: Long,
    ) : ChapterOutlineRow

    /**
     * A chapter.
     *
     * @property chapter the chapter itself.
     * @property index its 0-based place in the whole list the outline was built from, so a surface
     *   holding a parallel list of the same chapters reaches this one's entry.
     * @property number the number a reader sees: 1-based in audio order, running on across parts and
     *   starting again at 1 at each Book marker. With no Book markers it is simply [index] plus one.
     */
    data class ChapterRow(
        val chapter: Chapter,
        val index: Int,
        val number: Int,
    ) : ChapterOutlineRow
}

/**
 * The outline of an ordered chapter list, built on [groupChapters].
 *
 * Chapters before the first header of a tier sit above it with no header of their own, and a book
 * with no markers comes back as nothing but [ChapterOutlineRow.ChapterRow]s, so a flat book needs no
 * special case at the call site.
 */
fun List<Chapter>.toChapterOutline(): List<ChapterOutlineRow> {
    var index = 0
    return groupChapters().flatMap { book ->
        buildList {
            book.title?.let { title ->
                val opener =
                    book.parts
                        .first()
                        .chapters
                        .first()
                add(ChapterOutlineRow.BookHeader(title, opener.id, opener.startTime))
            }
            var number = 0
            book.parts.forEach { part ->
                part.title?.let { title ->
                    val opener = part.chapters.first()
                    add(
                        ChapterOutlineRow.PartHeader(
                            title = title,
                            chapterId = opener.id,
                            chapterCount = part.chapters.size,
                            durationMs = part.chapters.sumOf { it.duration },
                            startMs = opener.startTime,
                        ),
                    )
                }
                part.chapters.forEach { chapter -> add(ChapterOutlineRow.ChapterRow(chapter, index++, ++number)) }
            }
        }
    }
}
