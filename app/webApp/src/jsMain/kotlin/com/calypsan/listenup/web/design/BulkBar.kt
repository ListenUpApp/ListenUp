package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLElement

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
 * named group rather than loose buttons, one tab stop whose arrows move between them. On a phone it docks at the top of the viewport as a header;
 * wider, it floats at the foot of the content. On a phone an action with an icon shows the icon alone,
 * so every action fits 320px; its label stays its accessible name and tooltip.
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
    // A toolbar is one tab stop; the arrows move between its controls (roving tabindex, WAI-ARIA).
    val controls = actions.size + 1
    var current by remember { mutableIntStateOf(0) }
    if (current >= controls) current = 0
    var bar by remember { mutableStateOf<HTMLElement?>(null) }

    Div(attrs = {
        classes("bulk")
        attr("role", "toolbar")
        attr("aria-label", label)
        ref { element ->
            bar = element
            onDispose { bar = null }
        }
        onKeyDown { event ->
            val next = rovingTarget(event.key, current, controls) ?: return@onKeyDown
            event.preventDefault()
            current = next
            (bar?.querySelectorAll("button")?.item(next) as? HTMLElement)?.focus()
        }
    }) {
        Button(attrs = {
            classes("bulk-x")
            attr("type", "button")
            attr("aria-label", CANCEL_SELECTION)
            attr("title", CANCEL_SELECTION)
            tabIndex(if (current == 0) 0 else -1)
            onFocus { current = 0 }
            onClick { onClear() }
        }) {
            Icon(WebIcon.X, size = BULK_CLEAR_ICON_SIZE)
        }
        Span(attrs = { classes("bulk-n") }) { Text("$count selected") }
        Span(attrs = { classes("bulk-gap") }) {}
        actions.forEachIndexed { index, action ->
            val position = index + 1
            Button(attrs = {
                classes("bulk-b")
                attr("type", "button")
                // Named by its label in every layout: a phone shows the icon alone (05-patterns.css).
                attr("aria-label", action.label)
                attr("title", action.label)
                tabIndex(if (current == position) 0 else -1)
                onFocus { current = position }
                onClick { action.onClick() }
            }) {
                action.icon?.let { Icon(it, size = BULK_ACTION_ICON_SIZE) }
                Span(attrs = { classes("bulk-bl") }) { Text(action.label) }
            }
        }
    }
}

/** Where an arrow, Home or End moves focus among [count] toolbar controls from [from]; null for any other key. */
internal fun rovingTarget(
    key: String,
    from: Int,
    count: Int,
): Int? =
    when (key) {
        "ArrowRight", "ArrowDown" -> (from + 1) % count
        "ArrowLeft", "ArrowUp" -> (from - 1 + count) % count
        "Home" -> 0
        "End" -> count - 1
        else -> null
    }

/** "1 chapter selected", "3 chapters selected": the chapter selection toolbar's name (`selection.chapters_a11y_*`). */
fun chaptersSelectedLabel(count: Int): String = if (count == 1) "1 chapter selected" else "$count chapters selected"

/** "1 book selected", "3 books selected": a book selection toolbar's name (`selection.toolbar_a11y_*`). */
fun booksSelectedLabel(count: Int): String = if (count == 1) "1 book selected" else "$count books selected"

/** The bar's way out, the same words as every platform's selection bar. */
private const val CANCEL_SELECTION = "Cancel selection"

private const val BULK_ACTION_ICON_SIZE = 15

private const val BULK_CLEAR_ICON_SIZE = 16
