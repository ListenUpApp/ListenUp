package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.CheckboxInput
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Table
import org.jetbrains.compose.web.dom.Tbody
import org.jetbrains.compose.web.dom.Td
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Th
import org.jetbrains.compose.web.dom.Thead
import org.jetbrains.compose.web.dom.Tr
import org.w3c.dom.HTMLInputElement

/** Which edge a column's content sits against. Numeric columns go right, by convention. */
enum class ColumnAlign(
    internal val css: String,
) {
    Start("left"),
    End("right"),
}

/**
 * One column of a [DataTable].
 *
 * [cell] is a composable rather than a value getter so a column can render a status chip, a
 * progress bar or a link — which is what the comp's chapter and file tables actually do.
 *
 * [mono] marks a column as machine-produced: durations, sizes, offsets, codecs. It is the visual
 * rule that lets a column of digits line up, and it is the same signal the rest of the app uses
 * for server-produced text.
 */
class TableColumn<T>(
    val key: String,
    val label: String,
    val width: Int? = null,
    val align: ColumnAlign = ColumnAlign.Start,
    val mono: Boolean = false,
    val cell: @Composable (T) -> Unit,
)

/** Header checkbox state. [Some] renders the indeterminate dash rather than a tick. */
enum class SelectAllState {
    None,
    Some,
    All,
}

/**
 * What a [DataTable]'s selection checkboxes are called, for a screen reader.
 *
 * A checkbox with no name is announced as "checkbox, not checked" — true, and useless in a column of
 * forty. [all] names the header's ("Select all chapters"); [row] names each row's ("Select chapter 9").
 */
class SelectionLabel<T>(
    val all: String,
    val row: (T) -> String,
)

/**
 * The table.
 *
 * Foundations calls rows "the web's native unit and nothing in the kit had them" — this is the
 * component that makes the web client a management surface rather than a phone app in a browser.
 * Density comes from the `--row`/`--fs` custom properties, so the same table is comfortable on a
 * reading surface and dense on a working one without a second component.
 *
 * Selection and playing state are supplied as predicates rather than stored on the row type: the
 * row is a domain object, and whether it happens to be selected is a property of the view.
 *
 * Every control here is a real one. Selection is a native checkbox per row (and one in the header),
 * sorting is a `<button>` in each sortable header with `aria-sort` on the sorted one — so the whole
 * table works from the keyboard. [onRowClick] stays as a mouse convenience on the `<tr>`; the
 * checkbox is the keyboard's way to the same selection.
 *
 * [rowKey] names each row's stable identity, so a row keeps its DOM node and remembered state when
 * the rows reorder, insert or remove. Without it rows are matched by position.
 */
@Composable
fun <T> DataTable(
    columns: List<TableColumn<T>>,
    rows: List<T>,
    selectable: Boolean = false,
    isSelected: (T) -> Boolean = { false },
    isPlaying: (T) -> Boolean = { false },
    sortKey: String? = null,
    sortDescending: Boolean = false,
    allState: SelectAllState = SelectAllState.None,
    selectionLabel: SelectionLabel<T> = SelectionLabel(all = "Select all rows") { "Select row" },
    rowActions: List<WebIcon> = emptyList(),
    onRowClick: ((T) -> Unit)? = null,
    onToggleRow: ((T) -> Unit)? = null,
    onToggleAll: (() -> Unit)? = null,
    onSort: ((String) -> Unit)? = null,
    rowKey: ((T) -> Any)? = null,
) {
    Div(attrs = { classes("tblwrap") }) {
        Table(attrs = { classes("tbl") }) {
            Thead {
                HeaderRow(
                    columns = columns,
                    selectable = selectable,
                    sort = sortKey?.let { SortedBy(it, sortDescending) },
                    allState = allState,
                    selectAllLabel = selectionLabel.all,
                    hasRowActions = rowActions.isNotEmpty(),
                    onToggleAll = onToggleAll,
                    onSort = onSort,
                )
            }
            Tbody {
                rows.forEach { row ->
                    key(rowKey?.invoke(row)) {
                        BodyRow(
                            row = row,
                            columns = columns,
                            selectable = selectable,
                            selected = isSelected(row),
                            playing = isPlaying(row),
                            selectLabel = selectionLabel.row(row),
                            rowActions = rowActions,
                            onRowClick = onRowClick,
                            onToggleRow = onToggleRow,
                        )
                    }
                }
            }
        }
    }
}

/** The column a table is sorted by, and which way. */
private class SortedBy(
    val key: String,
    val descending: Boolean,
)

@Composable
private fun <T> HeaderRow(
    columns: List<TableColumn<T>>,
    selectable: Boolean,
    sort: SortedBy?,
    allState: SelectAllState,
    selectAllLabel: String,
    hasRowActions: Boolean,
    onToggleAll: (() -> Unit)?,
    onSort: ((String) -> Unit)?,
) {
    Tr {
        if (selectable) {
            Th(attrs = { style { property("width", "${SELECT_COLUMN_WIDTH}px") } }) {
                Checkbox(
                    checked = allState != SelectAllState.None,
                    indeterminate = allState == SelectAllState.Some,
                    label = selectAllLabel,
                    onToggle = onToggleAll,
                )
            }
        }
        columns.forEach { column ->
            HeaderCell(column = column, sort = sort?.takeIf { it.key == column.key }, onSort = onSort)
        }
        if (hasRowActions) {
            Th(attrs = { style { property("width", "${ROW_ACTIONS_COLUMN_WIDTH}px") } }) {}
        }
    }
}

@Composable
private fun <T> HeaderCell(
    column: TableColumn<T>,
    sort: SortedBy?,
    onSort: ((String) -> Unit)?,
) {
    Th(attrs = {
        if (sort != null) classes("srt")
        if (onSort != null) classes("can")
        // On the header cell itself, where a screen reader looks for it when announcing a column.
        sort?.let { attr("aria-sort", if (it.descending) "descending" else "ascending") }
        column.width?.let { style { property("width", "${it}px") } }
        style { property("text-align", column.align.css) }
    }) {
        if (onSort != null) {
            Button(attrs = {
                attr("type", "button")
                onClick { onSort(column.key) }
            }) { HeaderLabel(column.label, sort) }
        } else {
            // Inline rather than a new class, matching how the comp styles it. A class would be
            // fine — the sheet is ours to extend — but one-off layout glue does not earn a name in
            // a vocabulary every screen has to learn.
            Span(attrs = {
                style {
                    property("display", "inline-flex")
                    property("align-items", "center")
                    property("gap", "5px")
                }
            }) { HeaderLabel(column.label, sort) }
        }
    }
}

@Composable
private fun HeaderLabel(
    label: String,
    sort: SortedBy?,
) {
    Text(label)
    sort?.let {
        Icon(
            if (it.descending) WebIcon.ArrowDown else WebIcon.ArrowUp,
            size = SORT_ICON_SIZE,
            strokeWidth = SORT_ICON_STROKE,
        )
    }
}

@Composable
private fun <T> BodyRow(
    row: T,
    columns: List<TableColumn<T>>,
    selectable: Boolean,
    selected: Boolean,
    playing: Boolean,
    selectLabel: String,
    rowActions: List<WebIcon>,
    onRowClick: ((T) -> Unit)?,
    onToggleRow: ((T) -> Unit)?,
) {
    Tr(attrs = {
        if (selected) classes("sel")
        if (playing) classes("on")
        onRowClick?.let { click -> onClick { click(row) } }
    }) {
        if (selectable) {
            Td {
                Checkbox(
                    checked = selected,
                    indeterminate = false,
                    label = selectLabel,
                    onToggle = onToggleRow?.let { toggle -> { toggle(row) } },
                )
            }
        }
        columns.forEach { column ->
            Td(attrs = {
                if (column.mono || column.align == ColumnAlign.End) classes("num")
                style { property("text-align", column.align.css) }
            }) {
                column.cell(row)
            }
        }
        if (rowActions.isNotEmpty()) {
            Td {
                Span(attrs = { classes("rowact") }) {
                    rowActions.forEach { action ->
                        Button(attrs = { classes("iconbtn") }) {
                            Icon(action, size = ROW_ACTION_ICON_SIZE)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The selection checkbox: a native `<input type="checkbox">` with the kit's drawn box over it.
 *
 * It was a decorative `<span>` with the click on the table cell, which could not be reached or
 * toggled from the keyboard. Now the input is the control — Tab reaches it, Space toggles it, it is
 * announced by [label] with its state — and `.cbx` is only its picture, the same arrangement
 * [SwitchField] uses for its track.
 *
 * `indeterminate` is a DOM property with no attribute, so it is set on the element directly; that
 * is also what makes a screen reader say "mixed". `.cbx.ind` draws its dash with a `::after`
 * pseudo-element, so the indeterminate state carries no child — passing a tick as well would stack
 * two marks on each other.
 *
 * The click stops at the label: a checkbox inside a clickable row must not also count as a click
 * on the row.
 */
@Composable
fun Checkbox(
    checked: Boolean,
    indeterminate: Boolean,
    label: String,
    onToggle: (() -> Unit)? = null,
) {
    val input = remember { InputHolder() }
    // After every composition, not only on mount: the header moves between none, some and all as
    // rows are picked, and a property set once would keep announcing the first of them.
    SideEffect { input.element?.indeterminate = indeterminate }

    Label(attrs = {
        classes("cbx-hit")
        onClick { event -> event.stopPropagation() }
    }) {
        CheckboxInput(checked = checked) {
            classes("cbx-in")
            attr("aria-label", label)
            ref { element ->
                input.element = element
                element.indeterminate = indeterminate
                onDispose { input.element = null }
            }
            onChange { onToggle?.invoke() }
        }
        Span(attrs = {
            classes("cbx")
            if (checked) classes("on")
            if (indeterminate) classes("ind")
            attr("aria-hidden", "true")
        }) {
            if (checked && !indeterminate) {
                Icon(WebIcon.Check, size = CHECK_ICON_SIZE, strokeWidth = CHECK_ICON_STROKE)
            }
        }
    }
}

/** The checkbox's DOM node, for the one property Compose HTML cannot set: `indeterminate`. */
private class InputHolder {
    var element: HTMLInputElement? = null
}

private const val SELECT_COLUMN_WIDTH = 40

private const val ROW_ACTIONS_COLUMN_WIDTH = 96

private const val SORT_ICON_SIZE = 11

private const val SORT_ICON_STROKE = 2.4

private const val ROW_ACTION_ICON_SIZE = 16

private const val CHECK_ICON_SIZE = 12

private const val CHECK_ICON_STROKE = 3.0
