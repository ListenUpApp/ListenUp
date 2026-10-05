package com.calypsan.listenup.web.features.seriesedit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.presentation.seriesedit.ParentPickerDisabledReason
import com.calypsan.listenup.client.presentation.seriesedit.ParentPickerRow
import com.calypsan.listenup.client.presentation.seriesedit.SeriesEditUiEvent
import com.calypsan.listenup.client.presentation.seriesedit.SeriesEditUiState
import com.calypsan.listenup.web.design.Button
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.EmptyLook
import com.calypsan.listenup.web.design.EmptyState
import com.calypsan.listenup.web.design.Field
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.ModalDialog
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.features.seriesdetail.SERIES_PATH_SEPARATOR
import com.calypsan.listenup.web.features.seriesdetail.seriesBookCount
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLElement
import org.jetbrains.compose.web.dom.Button as DomButton

/**
 * "Move “Mistborn” into…" — the whole library as a tree, with what can't be chosen greyed out in
 * place rather than dropped, so the library's shape never jumps.
 *
 * A WAI-ARIA `role="tree"` inside the modal `<dialog>` (which traps focus and closes on Escape).
 * One row is a tab stop at a time; the arrows walk the rows, Right expands (or steps into a child),
 * Left collapses (or steps back to the parent), Home and End jump to the ends, and Enter or Space
 * chooses. A row that can't be chosen says `aria-disabled="true"` and points at its reason —
 * "Current", "This series", "Inside Mistborn" — with `aria-describedby`.
 *
 * Choosing sends `ParentSelected` and the ViewModel closes the picker at once; the Part of row shows
 * the busy indicator until sync brings the new parent back. While a search is typed the rows are
 * flat and each carries its place ("in Cosmere › Mistborn").
 */
@Composable
internal fun ParentPickerDialog(
    state: SeriesEditUiState,
    rows: List<ParentPickerRow>,
    onEvent: (SeriesEditUiEvent) -> Unit,
) {
    ModalDialog(
        open = true,
        // en.json's series.move_into_named
        title = "Move “${state.name}” into…",
        onDismiss = { onEvent(SeriesEditUiEvent.ParentPickerDismissed) },
        panelClass = "sh-dlg",
    ) {
        Field(
            label = "Search series",
            value = state.parentQuery,
            onInput = { onEvent(SeriesEditUiEvent.ParentQueryChanged(it)) },
            leading = WebIcon.Search,
            // en.json's series.merge_search_placeholder
            placeholder = "Search series…",
            id = "parent-query",
        )
        Div(attrs = { classes("sh-list") }) {
            TopLevelRow(isCurrent = state.parentId == null, onEvent = onEvent)
            DomButton(attrs = {
                classes("sh-row", "sh-pinned")
                attr("type", "button")
                onClick { onEvent(SeriesEditUiEvent.NewParentStarted) }
            }) {
                Icon(WebIcon.Plus, size = ICON)
                // en.json's series.new_parent
                Span(attrs = { classes("sh-row-n") }) { Text("New parent series…") }
            }
            if (rows.isEmpty() && state.parentQuery.isNotBlank()) {
                // en.json's series.merge_no_matches
                EmptyState(title = "No series match that search.", look = EmptyLook.Inline)
            } else {
                SeriesTree(rows, seriesName = state.name, searching = state.parentQuery.isNotBlank(), onEvent = onEvent)
            }
        }
        Div(attrs = { classes("dlg-actions") }) {
            Button(kind = ButtonKind.Secondary, onClick = { onEvent(SeriesEditUiEvent.ParentPickerDismissed) }) {
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun TopLevelRow(
    isCurrent: Boolean,
    onEvent: (SeriesEditUiEvent) -> Unit,
) {
    DomButton(attrs = {
        classes("sh-row", "sh-pinned")
        attr("type", "button")
        if (isCurrent) {
            classes("is-off")
            attr("aria-disabled", "true")
            attr("aria-describedby", "parent-top-reason")
        }
        onClick { if (!isCurrent) onEvent(SeriesEditUiEvent.ParentCleared) }
    }) {
        Icon(WebIcon.Layers, size = ICON)
        // en.json's series.top_level_no_parent
        Span(attrs = { classes("sh-row-n") }) { Text("Top level (no parent)") }
        if (isCurrent) {
            // en.json's series.picker_current
            Span(attrs = {
                classes("sh-chip")
                attr("id", "parent-top-reason")
            }) { Text("Current") }
        }
    }
}

@Composable
private fun SeriesTree(
    rows: List<ParentPickerRow>,
    seriesName: String,
    searching: Boolean,
    onEvent: (SeriesEditUiEvent) -> Unit,
) {
    // The one row that is a tab stop. Starts on the first row the reader can choose.
    var activeId by remember { mutableStateOf<String?>(null) }
    val active = rows.firstOrNull { it.id == activeId } ?: rows.firstOrNull { it.isSelectable } ?: rows.firstOrNull()

    Div(attrs = {
        classes("sh-tree")
        attr("role", "tree")
        attr("aria-label", "Series")
    }) {
        rows.forEachIndexed { index, row ->
            key(row.id) {
                TreeRow(
                    row = row,
                    seriesName = seriesName,
                    searching = searching,
                    isTabStop = row.id == active?.id,
                    onFocus = { activeId = row.id },
                    onKey = { keyName, target -> treeKey(keyName, index, rows, target, onEvent) { activeId = it } },
                    onEvent = onEvent,
                )
            }
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun TreeRow(
    row: ParentPickerRow,
    seriesName: String,
    searching: Boolean,
    isTabStop: Boolean,
    onFocus: () -> Unit,
    onKey: (String, HTMLElement?) -> Boolean,
    onEvent: (SeriesEditUiEvent) -> Unit,
) {
    val reasonId = "parent-reason-${row.id}"
    val reason = row.disabledReason?.let { reasonLabel(it, seriesName) }
    val expandable = row.hasChildren && !searching
    Div(attrs = {
        classes("sh-row", "sh-node")
        if (!row.isSelectable) classes("is-off")
        attr("role", "treeitem")
        attr("data-node-id", row.id)
        attr("aria-level", (row.depth + 1).toString())
        if (expandable) attr("aria-expanded", row.isExpanded.toString())
        attr("aria-selected", "false")
        if (reason != null) {
            attr("aria-disabled", "true")
            attr("aria-describedby", reasonId)
        }
        tabIndex(if (isTabStop) 0 else -1)
        style { property("--depth", row.depth.toString()) }
        addEventListener("focus") { onFocus() }
        onClick { if (row.isSelectable) onEvent(SeriesEditUiEvent.ParentSelected(row.id)) }
        onKeyDown { event ->
            if (onKey(event.key, event.currentTarget as? HTMLElement)) event.preventDefault()
        }
    }) {
        Chevron(row, expandable, onEvent)
        Div(attrs = { classes("sh-node-text") }) {
            Span(attrs = { classes("sh-row-n") }) { Text(row.name) }
            if (searching && row.pathNames.isNotEmpty()) {
                // en.json's series.in_path
                Span(attrs = { classes("sh-row-m") }) {
                    Text("in " + row.pathNames.joinToString(SERIES_PATH_SEPARATOR))
                }
            }
        }
        if (reason != null) {
            Span(attrs = {
                classes("sh-chip")
                attr("id", reasonId)
            }) { Text(reason) }
        } else {
            Span(attrs = { classes("sh-row-m") }) { Text(rowCount(row)) }
        }
    }
}

/** The chevron is a pointer convenience; a keyboard expands with Right and collapses with Left. */
@Composable
private fun Chevron(
    row: ParentPickerRow,
    expandable: Boolean,
    onEvent: (SeriesEditUiEvent) -> Unit,
) {
    Span(attrs = {
        classes("sh-chev")
        attr("aria-hidden", "true")
        if (expandable) {
            if (row.isExpanded) classes("is-open")
            onClick { event ->
                event.stopPropagation()
                onEvent(SeriesEditUiEvent.ParentPickerNodeToggled(row.id))
            }
        }
    }) { if (expandable) Icon(WebIcon.ChevronRight, size = ICON) }
}

/** "Current", "This series", "Inside Mistborn" — en.json's `series.picker_current/self/inside`. */
internal fun reasonLabel(
    reason: ParentPickerDisabledReason,
    seriesName: String,
): String =
    when (reason) {
        ParentPickerDisabledReason.CURRENT_PARENT -> "Current"
        ParentPickerDisabledReason.THIS_SERIES -> "This series"
        ParentPickerDisabledReason.INSIDE_THIS_SERIES -> "Inside $seriesName"
    }

/** "5 series" for a parent (en.json's `series.subseries_count`), else "7 books". */
private fun rowCount(row: ParentPickerRow): String =
    if (row.subSeriesCount > 0) "${row.subSeriesCount} series" else seriesBookCount(row.bookCount)

/**
 * One key on the tree. Returns whether it was handled, so the caller can stop the browser's own
 * behaviour (scrolling, a click on Space) for exactly the keys the tree answers.
 */
@Suppress("CyclomaticComplexMethod", "LongParameterList")
private fun treeKey(
    keyName: String,
    index: Int,
    rows: List<ParentPickerRow>,
    target: HTMLElement?,
    onEvent: (SeriesEditUiEvent) -> Unit,
    onActive: (String) -> Unit,
): Boolean {
    val row = rows[index]

    fun focusRow(to: Int) {
        val next = rows.getOrNull(to) ?: return
        onActive(next.id)
        val tree = target?.closest("[role=tree]") ?: return
        (tree.querySelector("[data-node-id=\"${next.id}\"]") as? HTMLElement)?.focus()
    }
    when (keyName) {
        "ArrowDown" -> {
            focusRow(index + 1)
        }

        "ArrowUp" -> {
            focusRow(index - 1)
        }

        "Home" -> {
            focusRow(0)
        }

        "End" -> {
            focusRow(rows.lastIndex)
        }

        "ArrowRight" -> {
            when {
                row.hasChildren && !row.isExpanded -> onEvent(SeriesEditUiEvent.ParentPickerNodeToggled(row.id))
                row.isExpanded -> focusRow(index + 1)
            }
        }

        "ArrowLeft" -> {
            if (row.isExpanded) {
                onEvent(SeriesEditUiEvent.ParentPickerNodeToggled(row.id))
            } else {
                val parent = (index - 1 downTo 0).firstOrNull { rows[it].depth < row.depth }
                if (parent != null) focusRow(parent)
            }
        }

        "Enter", " " -> {
            if (row.isSelectable) onEvent(SeriesEditUiEvent.ParentSelected(row.id))
        }

        else -> {
            return false
        }
    }
    return true
}

/** The "New parent series" dialog: creates the parent where this series sits now, then moves it in. */
@Composable
internal fun NewParentDialog(
    state: SeriesEditUiState,
    onEvent: (SeriesEditUiEvent) -> Unit,
) {
    val draft = state.newParent ?: return
    NewSeriesNameDialog(
        // en.json's series.new_parent_title
        title = "New parent series",
        draft = draft,
        // en.json's series.new_parent_body
        body = "Creates “${draft.name.trim().ifEmpty { "…" }}” and moves ${state.name} into it.",
        // en.json's series.create_and_move
        confirmLabel = "Create and move",
        // en.json's series.move_into_existing
        useExistingLabel = "Move into it instead",
        onName = { onEvent(SeriesEditUiEvent.NewParentNameChanged(it)) },
        onConfirm = { onEvent(SeriesEditUiEvent.NewParentConfirmed) },
        onUseExisting = { id ->
            onEvent(SeriesEditUiEvent.ParentSelected(id))
            onEvent(SeriesEditUiEvent.NewParentDismissed)
        },
        onDismiss = { onEvent(SeriesEditUiEvent.NewParentDismissed) },
        idBase = "parent-new",
    )
}

private const val ICON = 16
