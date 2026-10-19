package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/** One action a [BulkBar] offers over the current selection. */
class BulkAction(
    val label: String,
    val icon: WebIcon? = null,
    val onClick: () -> Unit,
)

/**
 * The dark selection toolbar that appears while rows are selected: **Cancel selection** first, then
 * the count, then the actions that apply to the whole selection (spec §3.5). It is a labelled
 * `role=toolbar` — [label] says what is selected ("2 books selected") — so a screen reader hears one
 * named group rather than loose buttons. On a phone it docks at the top of the viewport as a header;
 * wider, it floats at the foot of the content.
 *
 * Actions are supplied by the surface — the bar never invents any, so a pane with no real bulk
 * operations yet shows only Cancel and the count.
 */
@Composable
fun BulkBar(
    count: Int,
    label: String,
    actions: List<BulkAction> = emptyList(),
    onClear: () -> Unit,
) {
    Div(attrs = {
        classes("bulk")
        attr("role", "toolbar")
        attr("aria-label", label)
    }) {
        Button(attrs = {
            classes("bulk-x")
            attr("type", "button")
            attr("aria-label", CANCEL_SELECTION)
            attr("title", CANCEL_SELECTION)
            onClick { onClear() }
        }) {
            Icon(WebIcon.X, size = BULK_CLEAR_ICON_SIZE)
        }
        Span(attrs = { classes("bulk-n") }) { Text("$count selected") }
        Span(attrs = { classes("bulk-gap") }) {}
        actions.forEach { action ->
            Button(attrs = {
                classes("bulk-b")
                attr("type", "button")
                onClick { action.onClick() }
            }) {
                action.icon?.let { Icon(it, size = BULK_ACTION_ICON_SIZE) }
                Text(action.label)
            }
        }
    }
}

/** "1 book selected", "3 books selected": a book selection toolbar's name. */
fun booksSelectedLabel(count: Int): String = if (count == 1) "1 book selected" else "$count books selected"

/** The bar's way out, the same words as every platform's selection bar. */
private const val CANCEL_SELECTION = "Cancel selection"

private const val BULK_ACTION_ICON_SIZE = 15

private const val BULK_CLEAR_ICON_SIZE = 16
