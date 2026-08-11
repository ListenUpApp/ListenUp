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
 * The floating bar that appears while rows are selected: the count, the actions that apply to
 * the whole selection, and a way out. Actions are supplied by the surface — the bar never
 * invents any, so a pane with no real bulk operations yet shows only the count and Clear.
 */
@Composable
fun BulkBar(
    count: Int,
    actions: List<BulkAction> = emptyList(),
    onClear: () -> Unit,
) {
    Div(attrs = { classes("bulk") }) {
        Span(attrs = { style { property("font-weight", "700") } }) { Text("$count selected") }
        Span(attrs = { style { property("flex", "1") } }) {}
        actions.forEach { action ->
            Button(attrs = {
                classes("bulk-b")
                onClick { action.onClick() }
            }) {
                action.icon?.let { Icon(it, size = BULK_ACTION_ICON_SIZE) }
                Text(action.label)
            }
        }
        Button(attrs = {
            classes("bulk-x")
            attr("title", "Clear selection")
            onClick { onClear() }
        }) {
            Icon(WebIcon.X, size = BULK_CLEAR_ICON_SIZE)
        }
    }
}

private const val BULK_ACTION_ICON_SIZE = 15

private const val BULK_CLEAR_ICON_SIZE = 16
