package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.window
import org.jetbrains.compose.web.attributes.AttrsScope
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.pointerevents.PointerEvent
import kotlin.math.abs

/**
 * A menu that opens on the element it belongs to rather than from a button: a right-click, the Menu key or Shift+F10
 * on the focused element, or a long press on a touchscreen (spec §3.1.6/§3.1.7). The panel is [PopupMenu]'s own
 * ([MenuPanel]): the same items, groups, arrows and top-layer placement.
 *
 * - A right-click opens it at the pointer and keeps the browser's own menu away. So does Chrome Android's long-press
 *   `contextmenu`, and Windows' and Linux's Menu key, which fire `contextmenu` on the focused element.
 * - The ContextMenu key and Shift+F10 open it from the keyboard too, hung from the element's [anchorSelector] (its
 *   cover), with focus on the first item.
 * - A 500ms touch press opens it, because iOS Safari fires no `contextmenu` at all. Moving 10px cancels the press,
 *   since that is a scroll, and the click that follows a long press is swallowed, so the book does not also open.
 * - While open, another `contextmenu` (Chrome Android fires one after the timer) is ignored.
 * - Escape closes it and gives focus back to the element; a press outside closes it.
 *
 * ⛔ Renders no menu when [items] is empty: a gesture that opens a blank sheet promises something and gives nothing.
 */
@Composable
fun ContextMenuHost(
    items: List<MenuAction>,
    label: String,
    anchorSelector: String? = null,
    content: @Composable ContextMenuScope.() -> Unit,
) {
    val state = remember { ContextMenuState() }
    state.enabled = items.isNotEmpty()
    state.anchorSelector = anchorSelector
    val scope = remember(state) { ContextMenuScope(state) }
    scope.content()

    val anchor = state.anchor
    if (anchor != null && items.isNotEmpty()) {
        val menuNode = remember { NodeHolder() }
        CloseOnOutsidePress(menuNode) { state.close(returnFocus = false) }
        MenuPanel(
            items = items,
            label = label,
            anchor = { anchor },
            onChosen = { state.close(returnFocus = true) },
            onKeyClose = { returnFocus -> state.close(returnFocus) },
            menuNode = menuNode,
        )
    }
    DisposableEffect(state) { onDispose { state.cancelPress() } }
}

/** What a [ContextMenuHost]'s content uses to make an element its target. */
class ContextMenuScope internal constructor(
    private val state: ContextMenuState,
) {
    /**
     * Makes this element the menu's target: right-click, the Menu key, Shift+F10 and a touch long-press open it.
     * Call it before the element's own click and key handlers, so a long press's click is swallowed before them.
     */
    fun AttrsScope<*>.contextMenuTarget() {
        onClick { event ->
            if (state.swallowNextClick) {
                state.swallowNextClick = false
                event.preventDefault()
                event.nativeEvent.stopImmediatePropagation()
            }
        }
        onContextMenu { event ->
            if (!state.enabled) return@onContextMenu
            event.preventDefault()
            val target = event.currentTarget as? HTMLElement ?: return@onContextMenu
            state.cancelPress()
            if (!state.isOpen) state.open(MenuAnchor.at(event.clientX.toDouble(), event.clientY.toDouble()), target)
        }
        onKeyDown { event ->
            val isMenuKey = event.key == "ContextMenu" || (event.key == "F10" && event.shiftKey)
            if (!isMenuKey || !state.enabled) return@onKeyDown
            event.preventDefault()
            event.nativeEvent.stopImmediatePropagation()
            val target = event.currentTarget as? HTMLElement ?: return@onKeyDown
            if (!state.isOpen) state.open(state.anchorOf(target), target)
        }
        // Compose HTML has no typed pointer listeners, so these take the raw event.
        addEventListener("pointerdown") { event ->
            val pointer = event.nativeEvent as? PointerEvent ?: return@addEventListener
            if (pointer.pointerType != "touch" || !state.enabled) return@addEventListener
            val target = pointer.currentTarget as? HTMLElement ?: return@addEventListener
            state.startPress(pointer.clientX.toDouble(), pointer.clientY.toDouble(), target)
        }
        addEventListener("pointermove") { event ->
            val pointer = event.nativeEvent as? MouseEvent ?: return@addEventListener
            state.movePress(pointer.clientX.toDouble(), pointer.clientY.toDouble())
        }
        addEventListener("pointerup") { state.cancelPress() }
        addEventListener("pointercancel") { state.cancelPress() }
    }
}

/** Where a [ContextMenuHost]'s menu is open, and the touch press that may yet open it. */
class ContextMenuState internal constructor() {
    internal var anchor by mutableStateOf<MenuAnchor?>(null)
        private set
    internal var enabled = true
    internal var anchorSelector: String? = null
    internal var swallowNextClick = false
    private var target: HTMLElement? = null
    private var pressTimer: Int? = null
    private var pressX = 0.0
    private var pressY = 0.0

    internal val isOpen: Boolean get() = anchor != null

    internal fun anchorOf(target: HTMLElement): MenuAnchor =
        MenuAnchor.of(anchorSelector?.let { target.querySelector(it) } ?: target)

    internal fun open(
        at: MenuAnchor,
        on: HTMLElement,
    ) {
        target = on
        anchor = at
    }

    internal fun close(returnFocus: Boolean) {
        anchor = null
        if (returnFocus) target?.focus()
    }

    internal fun startPress(
        x: Double,
        y: Double,
        on: HTMLElement,
    ) {
        cancelPress()
        pressX = x
        pressY = y
        swallowNextClick = false
        pressTimer =
            window.setTimeout({
                pressTimer = null
                swallowNextClick = true
                if (!isOpen) open(anchorOf(on), on)
            }, LONG_PRESS_MS)
    }

    internal fun movePress(
        x: Double,
        y: Double,
    ) {
        if (pressTimer != null && (abs(x - pressX) > MOVE_SLOP_PX || abs(y - pressY) > MOVE_SLOP_PX)) cancelPress()
    }

    internal fun cancelPress() {
        pressTimer?.let { window.clearTimeout(it) }
        pressTimer = null
    }
}

/** How long a touch must rest before it is a long press: the platform convention. */
private const val LONG_PRESS_MS = 500

/** How far a touch may wander and still be a press rather than a scroll. */
private const val MOVE_SLOP_PX = 10.0
