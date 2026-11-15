package com.calypsan.listenup.web.features.bookdetail

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.domain.chapter.ChapterOutlineRow
import com.calypsan.listenup.client.presentation.bookdetail.ChapterUiModel
import com.calypsan.listenup.web.features.nowplaying.spokenDuration
import org.jetbrains.compose.web.attributes.AttrsScope
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.H4
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLHeadingElement
import kotlin.time.Duration.Companion.milliseconds

/**
 * One line of the chapter list: a chapter's row, or the heading of the book or part the rows after it sit in.
 * A flat book is nothing but [Row]s; a marked one reads them off its outline through [toChapterLines].
 */
internal sealed interface ChapterLine {
    /** What keeps this line's DOM across recompositions. */
    val key: String

    /** A chapter, carrying the [number] the reader sees. */
    data class Row(
        val chapter: ChapterUiModel,
        val number: Int,
    ) : ChapterLine {
        override val key: String get() = chapter.id
    }

    /** A Book marker: the heading one level above that book's parts. */
    data class Book(
        val title: String,
        val chapterId: String,
    ) : ChapterLine {
        override val key: String get() = "book-$chapterId"
    }

    /** A part divider; [inBook] when a [Book] heads it, so it sits a heading level below. */
    data class Part(
        val header: ChapterOutlineRow.PartHeader,
        val inBook: Boolean,
    ) : ChapterLine {
        override val key: String get() = "part-${header.chapterId}"
    }
}

/**
 * These outline rows as the list draws them, each chapter row reaching its entry in [chapters] by its index in
 * the whole book (its number restarts at each Book marker, so it can't).
 */
internal fun List<ChapterOutlineRow>.toChapterLines(chapters: List<ChapterUiModel>): List<ChapterLine> {
    var inBook = false
    return mapNotNull { row ->
        when (row) {
            is ChapterOutlineRow.BookHeader -> {
                inBook = true
                ChapterLine.Book(row.title, row.chapterId)
            }

            is ChapterOutlineRow.PartHeader -> {
                ChapterLine.Part(row, inBook)
            }

            is ChapterOutlineRow.ChapterRow -> {
                chapters.getOrNull(row.index)?.let { ChapterLine.Row(it, row.number) }
            }
        }
    }
}

/** A book's heading: the level above its parts, and like them not a control. */
@Composable
internal fun BookHeading(book: ChapterLine.Book) {
    H3(attrs = { classes("bd-book") }) { Text(book.title) }
}

/**
 * A part divider: a heading over the part's rows, its title and a quiet "N chapters · length". It is not a
 * control and is never current; the current chapter's row is. A screen reader hears the length spelled out.
 */
@Composable
internal fun PartDivider(part: ChapterLine.Part) {
    val header = part.header
    val length = header.durationMs.milliseconds
    val attrs: AttrsScope<HTMLHeadingElement>.() -> Unit = {
        classes("bd-part")
        attr(
            "aria-label",
            "${header.title}, ${chapterCount(header.chapterCount)}, ${spokenDuration(header.durationMs)}",
        )
    }
    val content: @Composable () -> Unit = {
        Span(attrs = { classes("bd-part-t") }) { Text(header.title) }
        Span(attrs = { classes("bd-part-sub") }) {
            Text("${chapterCount(header.chapterCount)} · ${DurationFormatter.hoursMinutes(length)}")
        }
    }
    if (part.inBook) H4(attrs = attrs) { content() } else H3(attrs = attrs) { content() }
}

private fun chapterCount(count: Int): String = if (count == 1) "1 chapter" else "$count chapters"
