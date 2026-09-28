package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLElement
import org.w3c.dom.Node
import org.w3c.dom.events.Event

/** One entry in an [ActionsMenu] or any other [PopupMenu]. */
data class MenuAction(
    val label: String,
    val icon: WebIcon,
    val onSelect: () -> Unit,
)

/**
 * A trailing "more actions" button and the menu it opens.
 *
 * Extracted from Book Detail's own menu when the admin inbox needed the same control. Both are a
 * row or a header with one primary gesture plus a few secondary ones, and a nested `<button>` is
 * invalid inside a `<button>` — so the secondary actions live beside the primary target, not
 * inside it. iOS's inbox row reaches the same arrangement and says so in the same words.
 *
 * ⛔ Renders nothing when [items] is empty. A button that opens an empty menu is worse than no
 * button: it promises something to do and then shows a blank sheet.
 */
@Composable
fun ActionsMenu(
    items: List<MenuAction>,
    label: String = "More actions",
    enabled: Boolean = true,
) {
    if (items.isEmpty()) return
    PopupMenu(items = items, label = label, icon = WebIcon.Grip, triggerClass = "btn-sq", enabled = enabled)
}

/**
 * A button that opens a menu, with the whole WAI-ARIA menu-button contract — the one shape the
 * account menu and every [ActionsMenu] share, so the two cannot drift apart in how a keyboard
 * reaches them.
 *
 * - The trigger says `aria-haspopup="menu"` and whether it is expanded.
 * - Opening puts focus on the first item, because opening a menu from the keyboard should put the
 *   reader inside it, not leave them on the button they pressed.
 * - The arrows walk the items and wrap; Home and End jump to the ends.
 * - Escape closes and hands focus back to the trigger. Tab closes and lets focus move on.
 * - A press anywhere outside closes. Pointer-down rather than click, so the menu is gone before
 *   the press lands on whatever was underneath it.
 *
 * [tooltip] repeats [label] as a `title` for an icon-only trigger whose purpose is not obvious from
 * its glyph (the account shield).
 */
@Composable
fun PopupMenu(
    items: List<MenuAction>,
    label: String,
    icon: WebIcon,
    triggerClass: String,
    enabled: Boolean = true,
    tooltip: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    val anchor = remember { NodeHolder() }
    val trigger = remember { NodeHolder() }

    fun close(returnFocus: Boolean) {
        open = false
        if (returnFocus) trigger.element?.focus()
    }

    if (open) {
        DisposableEffect(Unit) {
            val onPointerDown: (Event) -> Unit = { event ->
                val target = event.target as? Node
                if (target == null || anchor.element?.contains(target) != true) open = false
            }
            document.addEventListener("pointerdown", onPointerDown)
            onDispose { document.removeEventListener("pointerdown", onPointerDown) }
        }
    }

    // .menu-anchor, not .f-wrap: the latter is the form-field wrapper and is width:100%, which
    // stretched a menu across the whole content area.
    Div(attrs = {
        classes("menu-anchor")
        ref { element ->
            anchor.element = element
            onDispose { anchor.element = null }
        }
        onKeyDown { event ->
            if (!open) return@onKeyDown
            when (event.key) {
                "Escape" -> {
                    event.preventDefault()
                    close(returnFocus = true)
                }

                // No preventDefault: Tab still moves focus on, the menu just stops hanging open
                // behind it.
                "Tab" -> close(returnFocus = false)
            }
        }
    }) {
        Button(attrs = {
            classes(triggerClass)
            attr("type", "button")
            attr("aria-label", label)
            if (tooltip) attr("title", label)
            attr("aria-haspopup", "menu")
            attr("aria-expanded", open.toString())
            if (!enabled) attr("disabled", "")
            ref { element ->
                trigger.element = element
                onDispose { trigger.element = null }
            }
            onClick { open = !open }
        }) { Icon(icon, size = MENU_ICON_SIZE) }

        if (open) {
            Div(attrs = {
                classes("menu")
                attr("role", "menu")
                attr("aria-label", label)
            }) {
                items.forEachIndexed { index, item ->
                    // ⛔ A real <button role="menuitem">: a clickable <div> is unreachable by
                    // keyboard and announces nothing.
                    Button(attrs = {
                        classes("menu-i")
                        attr("type", "button")
                        attr("role", "menuitem")
                        // Every item but the one with focus is out of the Tab order; the arrows are
                        // how a menu is walked.
                        attr("tabindex", if (index == 0) "0" else "-1")
                        if (index == 0) {
                            ref { element ->
                                element.focus()
                                onDispose { }
                            }
                        }
                        onKeyDown { event ->
                            rovingTarget(event.key, index, items.size, RovingAxis.Vertical)?.let { next ->
                                event.preventDefault()
                                event.currentTarget.focusSibling(":scope > [role=menuitem]", next)
                            }
                        }
                        onClick {
                            close(returnFocus = false)
                            item.onSelect()
                        }
                    }) {
                        Icon(item.icon, size = MENU_ICON_SIZE)
                        Text(item.label)
                    }
                }
            }
        }
    }
}

/** A DOM node captured by `ref`, for the two places a menu has to reach past composition. */
private class NodeHolder {
    var element: HTMLElement? = null
}

private const val MENU_ICON_SIZE = 18
