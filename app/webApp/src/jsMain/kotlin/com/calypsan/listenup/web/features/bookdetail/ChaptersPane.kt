package com.calypsan.listenup.web.features.bookdetail

import androidx.compose.runtime.Composable
import com.calypsan.listenup.web.design.BulkBar
import com.calypsan.listenup.web.design.ColumnAlign
import com.calypsan.listenup.web.design.DataTable
import com.calypsan.listenup.web.design.MetaEntry
import com.calypsan.listenup.web.design.MetaList
import com.calypsan.listenup.web.design.Panel
import com.calypsan.listenup.web.design.SelectAllState
import com.calypsan.listenup.web.design.TableColumn
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.I
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text

/**
 * The chapters workbench: a proportional chapter map, the selectable table, and an inspector
 * that follows a single selection. Selection is URL state (`?sel=9,10`) — it arrives parsed and
 * leaves through [onSelectionChange], which the caller writes back with `replace`.
 *
 * No waveform yet, deliberately: there is no audio pipeline on this platform, and painting
 * invented peaks would be a canvas artifact shipped as a feature. The chapter map draws only
 * what is true — the boundaries.
 */
@Composable
internal fun ChaptersPane(
    chapters: List<WebChapter>,
    selection: Set<Int>,
    onSelectionChange: (Set<Int>) -> Unit,
) {
    if (chapters.isEmpty()) {
        Panel(title = "Chapters") {
            InspectorHint("This book has no chapter marks.")
        }
        return
    }

    Div(attrs = { classes("bd-cols") }) {
        Div(attrs = { classes("bd-main") }) {
            ChapterMap(chapters, selection) { number ->
                onSelectionChange(selection.toggled(number))
            }

            if (selection.isNotEmpty()) {
                BulkBar(count = selection.size, onClear = { onSelectionChange(emptySet()) })
            }

            Panel(flush = true) {
                DataTable(
                    columns = CHAPTER_COLUMNS,
                    rows = chapters,
                    selectable = true,
                    isSelected = { it.number in selection },
                    allState =
                        when {
                            selection.isEmpty() -> SelectAllState.None
                            selection.size == chapters.size -> SelectAllState.All
                            else -> SelectAllState.Some
                        },
                    onToggleRow = { onSelectionChange(selection.toggled(it.number)) },
                    onToggleAll = {
                        val all = chapters.map { it.number }.toSet()
                        onSelectionChange(if (selection == all) emptySet() else all)
                    },
                )
            }
        }

        Div(attrs = { classes("bd-side") }) {
            Inspector(selection, chapters)
        }
    }
}

@Composable
private fun Inspector(
    selection: Set<Int>,
    chapters: List<WebChapter>,
) {
    Panel(title = "Chapter") {
        val single = selection.singleOrNull()?.let { number -> chapters.firstOrNull { it.number == number } }
        when {
            single != null -> {
                MetaList(
                    listOf(
                        MetaEntry("Title", single.title),
                        MetaEntry("Starts", formatClock(single.startSec), machine = true),
                        MetaEntry("Ends", formatClock(single.endSec), machine = true),
                        MetaEntry("Length", formatClock(single.durationSec), machine = true),
                    ),
                )
            }

            selection.isEmpty() -> {
                InspectorHint("Select a chapter to inspect it.")
            }

            else -> {
                InspectorHint("${selection.size} chapters selected.")
            }
        }
    }
}

@Composable
private fun InspectorHint(text: String) {
    P(attrs = {
        style {
            property("margin", "0")
            property("font-size", "13.5px")
            property("color", "var(--ink-3)")
            property("font-weight", "500")
        }
    }) {
        Text(text)
    }
}

/**
 * The book as a strip: one segment per chapter, width proportional to duration, the selection
 * in coral. Clicking a segment toggles that chapter, same as its row.
 */
@Composable
private fun ChapterMap(
    chapters: List<WebChapter>,
    selection: Set<Int>,
    onToggle: (Int) -> Unit,
) {
    Div(attrs = { classes("chmap") }) {
        chapters.forEach { chapter ->
            I(attrs = {
                if (chapter.number in selection) classes("on")
                attr("title", "${chapter.title} · ${formatClock(chapter.durationSec)}")
                style { property("flex-grow", chapter.durationSec.toString()) }
                onClick { onToggle(chapter.number) }
            }) {}
        }
    }
}

private fun Set<Int>.toggled(number: Int): Set<Int> = if (number in this) this - number else this + number

private val CHAPTER_COLUMNS =
    listOf(
        TableColumn<WebChapter>("n", "#", width = 46, mono = true) { Text(it.number.toString()) },
        TableColumn("title", "Title") { Text(it.title) },
        TableColumn("start", "Start", width = 96, align = ColumnAlign.End, mono = true) {
            Text(formatClock(it.startSec))
        },
        TableColumn("length", "Length", width = 88, align = ColumnAlign.End, mono = true) {
            Text(formatClock(it.durationSec))
        },
    )
