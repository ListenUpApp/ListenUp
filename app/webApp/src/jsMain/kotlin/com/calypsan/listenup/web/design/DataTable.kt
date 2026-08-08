package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Table
import org.jetbrains.compose.web.dom.Tbody
import org.jetbrains.compose.web.dom.Td
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Th
import org.jetbrains.compose.web.dom.Thead
import org.jetbrains.compose.web.dom.Tr

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
 * The table.
 *
 * Foundations calls rows "the web's native unit and nothing in the kit had them" — this is the
 * component that makes the web client a management surface rather than a phone app in a browser.
 * Density comes from the `--row`/`--fs` custom properties, so the same table is comfortable on a
 * reading surface and dense on a working one without a second component.
 *
 * Selection and playing state are supplied as predicates rather than stored on the row type: the
 * row is a domain object, and whether it happens to be selected is a property of the view.
 */
@Composable
fun <T> DataTable(
    columns: List<TableColumn<T>>,
    rows: List<T>,
    selectable: Boolean = false,
    isSelected: (T) -> Boolean = { false },
    isPlaying: (T) -> Boolean = { false },
    sortKey: String? = null,
    allState: SelectAllState = SelectAllState.None,
    rowActions: List<WebIcon> = emptyList(),
    onRowClick: ((T) -> Unit)? = null,
    onToggleRow: ((T) -> Unit)? = null,
    onToggleAll: (() -> Unit)? = null,
    onSort: ((String) -> Unit)? = null,
) {
    Div(attrs = { classes("tblwrap") }) {
        Table(attrs = { classes("tbl") }) {
            Thead {
                HeaderRow(
                    columns = columns,
                    selectable = selectable,
                    sortKey = sortKey,
                    allState = allState,
                    hasRowActions = rowActions.isNotEmpty(),
                    onToggleAll = onToggleAll,
                    onSort = onSort,
                )
            }
            Tbody {
                rows.forEach { row ->
                    BodyRow(
                        row = row,
                        columns = columns,
                        selectable = selectable,
                        selected = isSelected(row),
                        playing = isPlaying(row),
                        rowActions = rowActions,
                        onRowClick = onRowClick,
                        onToggleRow = onToggleRow,
                    )
                }
            }
        }
    }
}

@Composable
private fun <T> HeaderRow(
    columns: List<TableColumn<T>>,
    selectable: Boolean,
    sortKey: String?,
    allState: SelectAllState,
    hasRowActions: Boolean,
    onToggleAll: (() -> Unit)?,
    onSort: ((String) -> Unit)?,
) {
    Tr {
        if (selectable) {
            Th(attrs = {
                style { property("width", "${SELECT_COLUMN_WIDTH}px") }
                onToggleAll?.let { toggle -> onClick { toggle() } }
            }) {
                Checkbox(
                    checked = allState != SelectAllState.None,
                    indeterminate = allState == SelectAllState.Some,
                )
            }
        }
        columns.forEach { column ->
            HeaderCell(column = column, sorted = column.key == sortKey, onSort = onSort)
        }
        if (hasRowActions) {
            Th(attrs = { style { property("width", "${ROW_ACTIONS_COLUMN_WIDTH}px") } }) {}
        }
    }
}

@Composable
private fun <T> HeaderCell(
    column: TableColumn<T>,
    sorted: Boolean,
    onSort: ((String) -> Unit)?,
) {
    Th(attrs = {
        if (sorted) classes("srt", "can") else classes("can")
        column.width?.let { style { property("width", "${it}px") } }
        style { property("text-align", column.align.css) }
        onSort?.let { sort -> onClick { sort(column.key) } }
    }) {
        // Inline rather than a new class, matching how the comp styles it. A class would be
        // fine — the sheet is ours to extend — but one-off layout glue does not earn a name in
        // a vocabulary every screen has to learn.
        Span(attrs = {
            style {
                property("display", "inline-flex")
                property("align-items", "center")
                property("gap", "5px")
            }
        }) {
            Text(column.label)
            if (sorted) {
                Icon(WebIcon.ArrowDown, size = SORT_ICON_SIZE, strokeWidth = SORT_ICON_STROKE)
            }
        }
    }
}

@Composable
private fun <T> BodyRow(
    row: T,
    columns: List<TableColumn<T>>,
    selectable: Boolean,
    selected: Boolean,
    playing: Boolean,
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
            Td(attrs = { onToggleRow?.let { toggle -> onClick { toggle(row) } } }) {
                Checkbox(checked = selected, indeterminate = false)
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
 * The selection checkbox.
 *
 * `.cbx.ind` draws its dash with a `::after` pseudo-element, so the indeterminate state carries
 * no child — passing a tick as well would stack two marks on each other.
 */
@Composable
fun Checkbox(
    checked: Boolean,
    indeterminate: Boolean,
) {
    Span(attrs = {
        classes("cbx")
        if (checked) classes("on")
        if (indeterminate) classes("ind")
    }) {
        if (checked && !indeterminate) {
            Icon(WebIcon.Check, size = CHECK_ICON_SIZE, strokeWidth = CHECK_ICON_STROKE)
        }
    }
}

private const val SELECT_COLUMN_WIDTH = 40

private const val ROW_ACTIONS_COLUMN_WIDTH = 96

private const val SORT_ICON_SIZE = 11

private const val SORT_ICON_STROKE = 2.4

private const val ROW_ACTION_ICON_SIZE = 16

private const val CHECK_ICON_SIZE = 12

private const val CHECK_ICON_STROKE = 3.0
