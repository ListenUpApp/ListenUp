package com.calypsan.listenup.web.features.bookdetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.presentation.bookdetail.ChapterUiModel
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.WebIcon
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
 * Chapters that play from the chapter they name (spec §2.5): a table with start times on a desktop, button rows on a
 * phone, both collapsed to [preview] (the 5-row window around the current chapter, or the first five) with "Show all N
 * chapters" expanding in place. Every row's title is a button named "Play from chapter N: title"; the current row
 * carries `aria-current`. Rows are disabled whenever the book can't play. The header links to the chapter map (D35).
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
            H2(attrs = { classes("bd-ch-title") }) {
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
        if (wide) {
            Table(attrs = { classes("bd-ch-table") }) {
                Thead { Tr { CHAPTER_TABLE_HEADINGS.forEach { heading -> Th { Text(heading) } } } }
                Tbody {
                    shown.forEach { chapter ->
                        key(chapter.id) {
                            val number = numberById[chapter.id] ?: 0
                            Tr(attrs = { if (chapter.isCurrent) classes("is-current") }) {
                                Td(attrs = { classes("bd-ch-n") }) { Text(number.toString()) }
                                Td { ChapterButton(chapter, number, canPlay, onPlayFromChapter, withMeta = false) }
                                Td(attrs = { classes("bd-ch-d", "mono") }) {
                                    Text(formatClock((chapter.startMs / MILLIS_PER_SECOND).toInt()))
                                }
                                Td(attrs = { classes("bd-ch-d", "mono") }) { Text(chapter.duration) }
                            }
                        }
                    }
                }
            }
        } else {
            Div(attrs = { classes("bd-ch-list") }) {
                shown.forEach { chapter ->
                    key(chapter.id) {
                        ChapterButton(chapter, numberById[chapter.id] ?: 0, canPlay, onPlayFromChapter, withMeta = true)
                    }
                }
            }
        }
        if (!expanded && chapters.size > preview.size) {
            Button(attrs = {
                classes("bd-ch-all")
                attr("type", "button")
                onClick { expanded = true }
            }) { Text("Show all ${chapters.size} chapters") }
        }
    }
}

@Composable
private fun ChapterButton(
    chapter: ChapterUiModel,
    number: Int,
    canPlay: Boolean,
    onPlayFromChapter: (Long) -> Unit,
    withMeta: Boolean,
) {
    Button(attrs = {
        classes("bd-ch-row")
        if (chapter.isCurrent) classes("is-current")
        attr("type", "button")
        attr("aria-label", "Play from chapter $number: ${chapter.title}")
        if (chapter.isCurrent) attr("aria-current", "true")
        // Only when it can't play: a `disabled` attribute of any value disables the button.
        if (!canPlay) attr("disabled", "")
        onClick { onPlayFromChapter(chapter.startMs) }
    }) {
        if (withMeta) Span(attrs = { classes("bd-ch-n") }) { Text(number.toString()) }
        Span(attrs = { classes("bd-ch-t") }) { Text(chapter.title) }
        if (withMeta) Span(attrs = { classes("bd-ch-d", "mono") }) { Text(chapter.duration) }
        Icon(WebIcon.Play, size = PLAY_MARK_SIZE, attrs = { classes("bd-ch-play") })
    }
}

/** The Chapters section's element id. */
internal const val CHAPTERS_SECTION_ID = "bd-chapters"

/** The desktop table's columns (spec §2.5: the start time is what a desktop reader scans for). */
private val CHAPTER_TABLE_HEADINGS = listOf("#", "Title", "Starts", "Length")

private const val MILLIS_PER_SECOND = 1000

private const val PLAY_MARK_SIZE = 14
