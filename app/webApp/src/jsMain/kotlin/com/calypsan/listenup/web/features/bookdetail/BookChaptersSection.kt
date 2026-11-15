package com.calypsan.listenup.web.features.bookdetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.presentation.bookdetail.ChapterReaders
import com.calypsan.listenup.client.presentation.bookdetail.ChapterUiModel
import com.calypsan.listenup.client.presentation.bookdetail.GroupedChapters
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.WebIcon
import kotlin.time.Duration.Companion.seconds
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Table
import org.jetbrains.compose.web.dom.Tbody
import org.jetbrains.compose.web.dom.Td
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Th
import org.jetbrains.compose.web.dom.Thead
import org.jetbrains.compose.web.dom.Tr

/**
 * Chapters that play from the chapter they name: a table with start times on a desktop, button rows on a
 * phone, both collapsed to [preview] (the 5-row window around the current chapter, or the first five) with "Show all N
 * chapters" expanding in place and "Show fewer chapters" collapsing it. Every row's title is a button named "Play from chapter N: title"; the current row
 * carries `aria-current`. Rows are disabled whenever the book can't play. The header links to the chapter map.
 *
 * A marked book ([grouped]) draws the same rows, every chapter it has, with its book headings and part dividers
 * between them: the desktop's one table keeps its columns lined up from part to part.
 */
@Composable
internal fun BookChaptersSection(
    bookId: String,
    chapters: List<ChapterUiModel>,
    preview: List<ChapterUiModel>,
    canPlay: Boolean,
    wide: Boolean,
    onPlayFromChapter: (Long) -> Unit,
    onOpenChapterMap: () -> Unit,
    grouped: GroupedChapters? = null,
    readers: ChapterReadersLookup = ChapterReadersLookup.None,
) {
    var expanded by remember(bookId) { mutableStateOf(false) }
    val shown = if (expanded) chapters else preview
    // A chapter's number is its place in the whole book, not in the window.
    val numberById = remember(chapters) { chapters.withIndex().associate { (i, chapter) -> chapter.id to i + 1 } }
    Section(attrs = {
        id(CHAPTERS_SECTION_ID)
        classes("bd-ch")
    }) {
        Div(attrs = { classes("bd-ch-head") }) {
            H2(attrs = { classes("bd-sec-title") }) {
                Text("Chapters ")
                Span(attrs = { classes("bd-ch-count") }) { Text(chapters.size.toString()) }
            }
            // The href is for a middle-click or a copied link; a plain click goes through the router.
            A(href = "/book/$bookId/map", attrs = {
                classes("bd-chmap-link")
                onClick {
                    it.preventDefault()
                    onOpenChapterMap()
                }
            }) { Text("Chapter map") }
        }
        val lines =
            grouped?.let { (if (expanded) it.rows else it.previewRows).toChapterLines(chapters) }
                ?: shown.map { ChapterLine.Row(it, numberById[it.id] ?: 0) }
        Div(attrs = { classes("bd-ch-body") }) {
            if (wide) {
                ChapterTable(lines, canPlay, onPlayFromChapter, readers)
            } else {
                Div(attrs = { classes("bd-ch-list") }) {
                    lines.forEach { line ->
                        key(line.key) {
                            when (line) {
                                is ChapterLine.Row -> {
                                    ChapterButton(
                                        line.chapter,
                                        line.number,
                                        canPlay,
                                        onPlayFromChapter,
                                        withMeta = true,
                                        readers = readers.onChapter(line.chapter.id),
                                    )
                                }

                                is ChapterLine.Book -> {
                                    BookHeading(line)
                                }

                                is ChapterLine.Part -> {
                                    PartDivider(line)
                                }
                            }
                        }
                    }
                }
            }
            if (chapters.size > preview.size) {
                ShowAllChaptersButton(
                    total = chapters.size,
                    expanded = expanded,
                    outside = if (expanded) null else readers.beyond(preview.map { it.id }),
                    onToggle = { expanded = !expanded },
                )
            }
        }
    }
}

/**
 * The desktop chapter table: number, the chapter's play button, its start time and its length. A heading spans
 * every column, so one table holds every part and the columns line up across them.
 */
@Composable
private fun ChapterTable(
    lines: List<ChapterLine>,
    canPlay: Boolean,
    onPlayFromChapter: (Long) -> Unit,
    readers: ChapterReadersLookup,
) {
    Table(attrs = { classes("bd-ch-table") }) {
        Thead { Tr { CHAPTER_TABLE_HEADINGS.forEach { heading -> Th { Text(heading) } } } }
        Tbody {
            lines.forEach { line ->
                key(line.key) {
                    when (line) {
                        is ChapterLine.Row -> ChapterTableRow(line, canPlay, onPlayFromChapter, readers)
                        is ChapterLine.Book -> HeadingTableRow { BookHeading(line) }
                        is ChapterLine.Part -> HeadingTableRow { PartDivider(line) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterTableRow(
    line: ChapterLine.Row,
    canPlay: Boolean,
    onPlayFromChapter: (Long) -> Unit,
    readers: ChapterReadersLookup,
) {
    val chapter = line.chapter
    Tr(attrs = { if (chapter.isCurrent) classes("is-current") }) {
        Td(attrs = { classes("bd-ch-n") }) { Text(line.number.toString()) }
        Td {
            ChapterButton(
                chapter,
                line.number,
                canPlay,
                onPlayFromChapter,
                withMeta = false,
                readers = readers.onChapter(chapter.id),
            )
        }
        Td(attrs = { classes("bd-ch-d", "mono") }) {
            Text(DurationFormatter.playbackClock((chapter.startMs / MILLIS_PER_SECOND).toInt().seconds))
        }
        Td(attrs = { classes("bd-ch-d", "mono") }) { Text(chapter.duration) }
    }
}

/** A book heading or part divider as a table row: one cell across every column. */
@Composable
private fun HeadingTableRow(heading: @Composable () -> Unit) {
    Tr(attrs = { classes("bd-part-tr") }) {
        Td(attrs = {
            classes("bd-ch-div")
            attr("colspan", CHAPTER_TABLE_HEADINGS.size.toString())
        }) { heading() }
    }
}

/**
 * "Show all N chapters" / "Show fewer chapters", expanding in place; aria-expanded says which. Collapsed, it carries
 * [outside]: everyone on a chapter the window leaves out.
 */
@Composable
private fun ShowAllChaptersButton(
    total: Int,
    expanded: Boolean,
    outside: ChapterReaders?,
    onToggle: () -> Unit,
) {
    Button(attrs = {
        classes("bd-ch-all")
        attr("type", "button")
        attr("aria-expanded", expanded.toString())
        outside?.let { attr("aria-describedby", BEYOND_READERS_ID) }
        onClick { onToggle() }
    }) {
        Span { Text(if (expanded) "Show fewer chapters" else "Show all $total chapters") }
        outside?.let { ChapterReadersStack(it) }
    }
    // Beside the button, not in it: inside, the sentence would become part of the button's name.
    outside?.let { ChapterReadersSentence(it, BEYOND_READERS_ID) }
}

@Composable
internal fun ChapterButton(
    chapter: ChapterUiModel,
    number: Int,
    canPlay: Boolean,
    onPlayFromChapter: (Long) -> Unit,
    withMeta: Boolean,
    readers: ChapterReaders? = null,
) {
    val sentenceId = chapterReadersId(chapter.id)
    Button(attrs = {
        classes("bd-ch-row")
        if (chapter.isCurrent) classes("is-current")
        attr("type", "button")
        attr("aria-label", "Play from chapter $number: ${chapter.title}")
        if (chapter.isCurrent) attr("aria-current", "true")
        readers?.let { attr("aria-describedby", sentenceId) }
        // Only when it can't play: a `disabled` attribute of any value disables the button.
        if (!canPlay) attr("disabled", "")
        onClick { onPlayFromChapter(chapter.startMs) }
    }) {
        if (withMeta) {
            // A phone row (board): number, title, length, then the play mark.
            Span(attrs = { classes("bd-ch-n") }) { Text(number.toString()) }
            Span(attrs = { classes("bd-ch-t") }) { Text(chapter.title) }
            readers?.let { ChapterReadersStack(it) }
            Span(attrs = { classes("bd-ch-d", "mono") }) { Text(chapter.duration) }
            Icon(WebIcon.Play, size = PLAY_MARK_SIZE, attrs = { classes("bd-ch-play") })
        } else {
            // A desktop title cell (board): the play mark leads the title, the readers follow it.
            Icon(WebIcon.Play, size = PLAY_MARK_SIZE, attrs = { classes("bd-ch-play") })
            Span(attrs = { classes("bd-ch-t") }) { Text(chapter.title) }
            readers?.let { ChapterReadersStack(it) }
        }
    }
    readers?.let { ChapterReadersSentence(it, sentenceId) }
}

/** The id of the sentence that describes [chapterId]'s row. */
internal fun chapterReadersId(chapterId: String): String = "bd-chr-$chapterId"

/** The sentence describing "Show all N chapters": who is on a chapter the collapsed list leaves out. */
private const val BEYOND_READERS_ID = "bd-chr-beyond"

/** The Chapters section's element id. */
internal const val CHAPTERS_SECTION_ID = "bd-chapters"

/** The desktop table's columns (the start time is what a desktop reader scans for). */
private val CHAPTER_TABLE_HEADINGS = listOf("#", "Title", "Starts", "Length")

private const val MILLIS_PER_SECOND = 1000

internal const val PLAY_MARK_SIZE = 14
