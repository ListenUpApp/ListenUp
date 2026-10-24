package com.calypsan.listenup.web.design

import org.w3c.dom.Element
import org.w3c.dom.HTMLElement

/** The on-screen box a panel hangs from: a trigger's, a card's, or a pointer's point (a box of no size). */
internal data class MenuAnchor(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    companion object {
        /** [element]'s box as it stands on screen now. */
        fun of(element: Element): MenuAnchor {
            val rect = element.getBoundingClientRect()
            return MenuAnchor(rect.left, rect.top, rect.right, rect.bottom)
        }

        /** The point a pointer pressed at. */
        fun at(
            x: Double,
            y: Double,
        ): MenuAnchor = MenuAnchor(x, y, x, y)
    }
}

/**
 * Puts [this] in the top layer, above every stacking context and outside every clip. Where the
 * popover API is missing, the panel's own `position: fixed` is the fallback, which still escapes
 * every clip but a transformed ancestor's.
 */
internal fun HTMLElement.showInTopLayer() {
    val element: dynamic = this
    if (jsTypeOf(element.showPopover) == "function") element.showPopover()
}

/** Shows [this] again in the top layer if an attribute pass closed it; true when it had to. */
internal fun HTMLElement.reopenInTopLayer(): Boolean {
    val element: dynamic = this
    val closed = jsTypeOf(element.showPopover) == "function" && !(element.matches(":popover-open") as Boolean)
    if (closed) element.showPopover()
    return closed
}

/** Focuses [this] without scrolling any box to reveal it: a panel placed first must not drag its trigger away. */
internal fun HTMLElement.focusWithoutScrolling() {
    asDynamic().focus(js("({ preventScroll: true })"))
}

/**
 * Places [panel] against [anchor]: below it and from its left edge by preference, from its right edge when the left
 * would cross the viewport's, above it when only above has room, and — when neither side has room for all of it — on
 * the roomier side, scrolling within the space there (`max-height`).
 *
 * Measured rather than guessed from where the trigger sits, because only the rendered panel knows how big its content
 * made it. One implementation for every menu ([MenuPanel]) and every popover (the player's panels), so the two cannot
 * disagree about which side of a trigger they open on or how close to the viewport's edge they may come.
 */
internal fun placeAgainst(
    panel: HTMLElement,
    anchor: MenuAnchor,
) {
    val viewport = panel.ownerDocument?.documentElement ?: return
    val viewportWidth = viewport.clientWidth.toDouble()
    val viewportHeight = viewport.clientHeight.toDouble()

    panel.style.removeProperty("max-height")
    val width = panel.offsetWidth.toDouble()
    val height = panel.offsetHeight.toDouble()

    val fromLeft = anchor.left
    val left =
        (if (fromLeft + width > viewportWidth - VIEWPORT_GUTTER) anchor.right - width else fromLeft)
            .coerceAtMost(viewportWidth - VIEWPORT_GUTTER - width)
            .coerceAtLeast(VIEWPORT_GUTTER)

    val below = anchor.bottom + TRIGGER_GAP
    val roomBelow = viewportHeight - VIEWPORT_GUTTER - below
    val roomAbove = anchor.top - TRIGGER_GAP - VIEWPORT_GUTTER
    val opensBelow = height <= roomBelow || (height > roomAbove && roomBelow >= roomAbove)
    val room = if (opensBelow) roomBelow else roomAbove
    if (height > room) panel.style.setProperty("max-height", "${room.coerceAtLeast(0.0)}px")
    val top = if (opensBelow) below else anchor.top - TRIGGER_GAP - minOf(height, room)

    panel.style.setProperty("left", "${left}px")
    panel.style.setProperty("top", "${top}px")
}

/** How close to the viewport's edge a panel may come before it flips to the other side. */
private const val VIEWPORT_GUTTER = 8.0

/** The space between a trigger and the panel hanging from it. */
private const val TRIGGER_GAP = 6.0
