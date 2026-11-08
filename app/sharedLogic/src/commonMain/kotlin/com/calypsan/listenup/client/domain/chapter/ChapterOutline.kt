package com.calypsan.listenup.client.domain.chapter

import com.calypsan.listenup.client.domain.model.Chapter

/**
 * One line of a book's chapter list, in reading order.
 *
 * Flat rather than nested because every surface draws a flat list and a nested shape would make
 * each of them walk it. [toChapterOutline] is the only producer, so no platform derives grouping
 * itself.
 */
sealed interface ChapterOutlineRow {
    /**
     * A Book marker.
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
     * A Part marker.
     *
     * @property title the name given to the marker.
     * @property chapterId the chapter carrying the marker. When that chapter folded, this is how the
     *   header still reaches it, because no [ChapterRow] will.
     * @property chapterCount chapters listed under this header: a folded chapter is not counted,
     *   because the reader cannot see a row for it.
     * @property durationMs the whole part's length, folded chapter included: that audio still plays.
     * @property startMs where the part begins.
     * @property containsCurrent whether the current chapter is in this part, folded or not.
     * @property isCurrentChapter whether the current chapter is the one folded into this header. It
     *   has no row of its own to light, so this header shows as current in its place.
     */
    data class PartHeader(
        val title: String,
        val chapterId: String,
        val chapterCount: Int,
        val durationMs: Long,
        val startMs: Long,
        val containsCurrent: Boolean,
        val isCurrentChapter: Boolean = false,
    ) : ChapterOutlineRow {
        /**
         * Whether this part is open: as the reader left it, per [overrides], or with no override
         * following the current chapter.
         */
        fun isOpen(overrides: Map<String, Boolean>): Boolean = overrides[chapterId] ?: containsCurrent

        /** [overrides] with this part flipped, so it stays that way wherever playback goes next. */
        fun toggled(overrides: Map<String, Boolean>): Map<String, Boolean> =
            overrides + (chapterId to !isOpen(overrides))
    }

    /**
     * A chapter.
     *
     * @property chapter the chapter itself.
     * @property number its 1-based position in the whole book. A fold never changes it: the rows
     *   after a folded chapter keep the numbers they have everywhere else.
     * @property partId the [PartHeader.chapterId] of the part this row sits in; null outside any part.
     *   It is how a surface knows what a closed part hides without walking the list itself.
     */
    data class ChapterRow(
        val chapter: Chapter,
        val number: Int,
        val partId: String? = null,
    ) : ChapterOutlineRow
}

/** What an outline does with a chapter that opens a part and only announces its name. */
enum class AnnouncementChapters {
    /** The header takes its place: listeners are not told "Part I" twice in a row. */
    Fold,

    /** It stays a row: the editor shows every chapter, because every chapter can be edited. */
    Keep,
}

/**
 * The outline of an ordered chapter list, built on [groupChapters].
 *
 * Chapters before the first header of a tier sit above it with no header of their own, and a book
 * with no markers comes back as nothing but [ChapterOutlineRow.ChapterRow]s, so a flat book needs no
 * special case at the call site.
 *
 * @param currentChapterId the chapter to report through [ChapterOutlineRow.PartHeader.containsCurrent].
 * @param announcements whether a chapter named for the part it opens folds into the header.
 */
fun List<Chapter>.toChapterOutline(
    currentChapterId: String? = null,
    announcements: AnnouncementChapters = AnnouncementChapters.Fold,
): List<ChapterOutlineRow> {
    val numbers = withIndex().associate { (index, chapter) -> chapter.id to index + 1 }
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
            book.parts.forEach { part -> addAll(part.outlineRows(numbers, currentChapterId, announcements)) }
        }
    }
}

/** These rows with the chapters of closed parts taken out; see [ChapterOutlineRow.PartHeader.isOpen]. */
fun List<ChapterOutlineRow>.withOpenParts(overrides: Map<String, Boolean>): List<ChapterOutlineRow> {
    val closed =
        filterIsInstance<ChapterOutlineRow.PartHeader>()
            .filterNot { it.isOpen(overrides) }
            .mapTo(mutableSetOf()) { it.chapterId }
    return filterNot { it is ChapterOutlineRow.ChapterRow && it.partId in closed }
}

private fun PartGroup.outlineRows(
    numbers: Map<String, Int>,
    currentChapterId: String?,
    announcements: AnnouncementChapters,
): List<ChapterOutlineRow> {
    val listed = if (announcements == AnnouncementChapters.Fold) withoutAnnouncement() else chapters
    val opener = chapters.first()
    val partId = opener.id.takeIf { title != null }
    val rows = listed.map { ChapterOutlineRow.ChapterRow(it, numbers.getValue(it.id), partId) }
    val partTitle = title ?: return rows
    val header =
        ChapterOutlineRow.PartHeader(
            title = partTitle,
            chapterId = opener.id,
            chapterCount = listed.size,
            durationMs = chapters.sumOf { it.duration },
            startMs = opener.startTime,
            containsCurrent = chapters.any { it.id == currentChapterId },
            isCurrentChapter = opener.id == currentChapterId && listed.none { it.id == opener.id },
        )
    return listOf(header) + rows
}

/** This part's chapters, less the opening one when it only announces the part's name. */
private fun PartGroup.withoutAnnouncement(): List<Chapter> {
    val partTitle = title?.trim() ?: return chapters
    return if (chapters
            .first()
            .title
            .trim()
            .equals(partTitle, ignoreCase = true)
    ) {
        chapters.drop(1)
    } else {
        chapters
    }
}
