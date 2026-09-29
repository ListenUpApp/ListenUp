package com.calypsan.listenup.web.design

import org.w3c.dom.HTMLElement
import org.w3c.dom.events.EventTarget

/** Which pair of arrow keys walks a composite control: tabs and strips run across, menus run down. */
enum class RovingAxis(
    internal val next: String,
    internal val previous: String,
) {
    Horizontal("ArrowRight", "ArrowLeft"),
    Vertical("ArrowDown", "ArrowUp"),
}

/**
 * The index [key] moves to from [current] among [count] items, or null when the key is not one a
 * composite control answers.
 *
 * The WAI-ARIA keyboard contract shared by tablists, menus and one-stop strips: the arrows along
 * [axis] step and wrap at the ends, Home and End jump to the ends. One function so a tab strip and a
 * menu cannot disagree about what End means.
 */
fun rovingTarget(
    key: String,
    current: Int,
    count: Int,
    axis: RovingAxis,
): Int? {
    if (count == 0) return null
    return when (key) {
        axis.next -> (current + 1).mod(count)
        axis.previous -> (current - 1).mod(count)
        "Home" -> 0
        "End" -> count - 1
        else -> null
    }
}

/**
 * Focuses the [index]th element matching [selector] among [this] element's siblings — the item a
 * roving key just moved to.
 *
 * Reads the DOM rather than holding a ref per item: the items are the same nodes before and after
 * the move, and a list of refs would be one more thing to keep in step with the list it mirrors.
 */
fun EventTarget?.focusSibling(
    selector: String,
    index: Int,
) {
    val parent = (this as? HTMLElement)?.parentElement ?: return
    (parent.querySelectorAll(selector).item(index) as? HTMLElement)?.focus()
}
