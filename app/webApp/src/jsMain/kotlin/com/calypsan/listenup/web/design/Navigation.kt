package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.B
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * One tab of a [Tabs] strip.
 *
 * [count] is the badge a tab carries when it stands for a countable set — "Chapters 44",
 * "Files 3". It stays null for tabs that are a view rather than a collection.
 */
class TabItem(
    val key: String,
    val label: String,
    val icon: WebIcon? = null,
    val count: String? = null,
)

/**
 * The tab strip across a record.
 *
 * Tab identity is a [key] rather than an index because it goes in the URL — `?tab=chapters` is
 * part of the page contract, so that a link to a specific pane can be shared. Foundations is
 * explicit that nothing which changes what you see may hide in component state.
 */
@Composable
fun Tabs(
    items: List<TabItem>,
    active: String,
    onSelect: ((String) -> Unit)? = null,
) {
    Div(attrs = { classes("tabs") }) {
        items.forEach { item ->
            Div(attrs = {
                classes("tab")
                if (item.key == active) classes("on")
                onSelect?.let { select -> onClick { select(item.key) } }
            }) {
                item.icon?.let { Icon(it, size = TAB_ICON_SIZE) }
                Text(item.label)
                item.count?.let { count ->
                    Span(attrs = { classes("ct") }) { Text(count) }
                }
            }
        }
    }
}

/** One choice in a [SegmentedControl]. */
class SegmentItem(
    val key: String,
    val label: String,
    val icon: WebIcon? = null,
)

/**
 * A segmented control — a small, mutually-exclusive filter.
 *
 * Distinct from [Tabs] by weight, not mechanism: tabs switch what the page is showing, a segment
 * narrows what is already shown ("All 44 / Unheard 35 / Edited 2").
 */
@Composable
fun SegmentedControl(
    items: List<SegmentItem>,
    active: String,
    onSelect: ((String) -> Unit)? = null,
) {
    Div(attrs = { classes("seg") }) {
        items.forEach { item ->
            B(attrs = {
                if (item.key == active) classes("on")
                onSelect?.let { select -> onClick { select(item.key) } }
            }) {
                item.icon?.let { Icon(it, size = SEGMENT_ICON_SIZE) }
                Text(item.label)
            }
        }
    }
}

/**
 * A tag or filter chip.
 *
 * [onRemove] is what turns a tag into an applied-filter chip; supplying it adds the dismiss
 * affordance, so the same component covers "this book is Horror" and "you are filtering by
 * Horror".
 */
@Composable
fun Pill(
    label: String,
    selected: Boolean = false,
    icon: WebIcon? = null,
    onClick: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    Span(attrs = {
        classes("pill")
        if (selected) classes("on")
        onClick?.let { click -> onClick { click() } }
    }) {
        icon?.let { Icon(it, size = PILL_ICON_SIZE) }
        Text(label)
        if (onRemove != null) {
            Span(attrs = {
                classes("x")
                onClick { event ->
                    // Without this the click also reaches the pill itself, so removing a filter
                    // would toggle it on the way out.
                    event.stopPropagation()
                    onRemove()
                }
            }) {
                Icon(WebIcon.X, size = PILL_REMOVE_ICON_SIZE, strokeWidth = PILL_REMOVE_STROKE)
            }
        }
    }
}

private const val TAB_ICON_SIZE = 16

private const val SEGMENT_ICON_SIZE = 15

private const val PILL_ICON_SIZE = 13

private const val PILL_REMOVE_ICON_SIZE = 12

private const val PILL_REMOVE_STROKE = 2.2
