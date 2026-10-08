package com.calypsan.listenup.web.features.bookdetail

import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.web.design.BulkBar
import com.calypsan.listenup.web.design.ColumnAlign
import com.calypsan.listenup.web.design.DataTable
import com.calypsan.listenup.web.design.MetaEntry
import com.calypsan.listenup.web.design.MetaList
import com.calypsan.listenup.web.design.Panel
import com.calypsan.listenup.web.design.RovingAxis
import com.calypsan.listenup.web.design.SelectAllState
import com.calypsan.listenup.web.design.SelectionLabel
import com.calypsan.listenup.web.design.focusSibling
import com.calypsan.listenup.web.design.rovingTarget
import com.calypsan.listenup.web.design.TableColumn
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.P
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.WebIcon
import org.jetbrains.compose.web.dom.Button
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
    onEditChapters: (() -> Unit)? = null,
) {
    // ⛔ Offered on an unchaptered book too, and pointing at the editor's own empty state rather
    // than at nothing. A book with no marks is exactly the one that most needs the editor, and a
    // pane that only says "no chapter marks" leaves the reader with no way to add any.
    if (chapters.isEmpty()) {
        Panel(title = "Chapters") {
            InspectorHint("This book has no chapter marks.")
            onEditChapters?.let { EditChaptersButton(it) }
        }
        return
    }

    Div(attrs = { classes("bd-cols") }) {
        Div(attrs = { classes("bd-main") }) {
            // Absent for a reader who may not edit metadata — the editor's save would be refused.
            onEditChapters?.let { EditChaptersButton(it) }

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
                    selectionLabel = SelectionLabel(all = "Select all chapters") { "Select chapter ${it.number}" },
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
            property("font-size", "0.84375rem")
            property("color", "var(--ink-3)")
            property("font-weight", "500")
        }
    }) {
        Text(text)
    }
}

/**
 * The book as a strip: one segment per chapter, width proportional to duration, the selection
 * in coral. Pressing a segment toggles that chapter, same as its row.
 *
 * Each segment is a `<button aria-pressed>` — they were `<i onClick>`s, usable only with a mouse —
 * but the strip is ONE tab stop, with the arrows, Home and End walking it. Thirty-odd stops of
 * two-pixel segments ahead of the table would bury the table, which is the fuller way to the same
 * selection.
 */
@Composable
private fun ChapterMap(
    chapters: List<WebChapter>,
    selection: Set<Int>,
    onToggle: (Int) -> Unit,
) {
    var focused by remember(chapters.size) { mutableStateOf(0) }
    Div(attrs = {
        classes("chmap")
        attr("role", "group")
        attr("aria-label", "Chapter map")
    }) {
        chapters.forEachIndexed { index, chapter ->
            val summary = "${chapter.title} · ${formatClock(chapter.durationSec)}"
            Button(attrs = {
                if (chapter.number in selection) classes("on")
                attr("type", "button")
                attr("title", summary)
                attr("aria-label", "Chapter ${chapter.number}: $summary")
                attr("aria-pressed", (chapter.number in selection).toString())
                tabIndex(if (index == focused.coerceAtMost(chapters.lastIndex)) 0 else -1)
                style { property("flex-grow", chapter.durationSec.toString()) }
                onFocus { focused = index }
                onKeyDown { event ->
                    rovingTarget(event.key, index, chapters.size, RovingAxis.Horizontal)?.let { next ->
                        event.preventDefault()
                        focused = next
                        event.currentTarget.focusSibling(":scope > button", next)
                    }
                }
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

/** The way from reading the chapters to changing them. */
@Composable
private fun EditChaptersButton(onEditChapters: () -> Unit) {
    Div(attrs = { classes("bd-chapters-edit") }) {
        Button(kind = ButtonKind.Secondary, onClick = { onEditChapters() }) {
            Icon(WebIcon.Pencil, size = EDIT_ICON)
            Text("Edit chapters")
        }
    }
}

private const val EDIT_ICON = 16
