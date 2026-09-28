package com.calypsan.listenup.web.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.RovingAxis
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.focusSibling
import com.calypsan.listenup.web.design.rovingTarget
import kotlinx.browser.document
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLElement
import org.w3c.dom.Node
import org.w3c.dom.events.Event

/**
 * The way out.
 *
 * Deliberately small: account management belongs on Settings, and this exists so the auth arc is a
 * loop rather than a one-way door — without it, seeing the login screen a second time means
 * clearing `localStorage` by hand.
 */
@Composable
fun AccountMenu(
    onSignOut: () -> Unit,
    /**
     * Opens the signed-in listener's own profile, or null while the app does not yet know who that
     * is. Null renders no entry at all rather than a disabled one: the gap lasts a frame or two on
     * a cold start, and a control that appears greyed and then becomes live draws the eye to a
     * transition nobody needs to watch.
     */
    onOpenProfile: (() -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    val anchor = remember { ElementHolder() }
    val trigger = remember { ElementHolder() }

    fun close(returnFocus: Boolean) {
        open = false
        if (returnFocus) trigger.element?.focus()
    }

    if (open) {
        // A menu that stays open until its own trigger is pressed again reads as stuck. Pointer-down
        // rather than click so the menu is gone before the press lands on whatever was underneath.
        DisposableEffect(Unit) {
            val onPointerDown: (Event) -> Unit = { event ->
                val target = event.target as? Node
                if (target == null || anchor.element?.contains(target) != true) open = false
            }
            document.addEventListener("pointerdown", onPointerDown)
            onDispose { document.removeEventListener("pointerdown", onPointerDown) }
        }
    }

    val items =
        buildList {
            onOpenProfile?.let { openProfile -> add(AccountMenuItem("Your profile", WebIcon.Person, openProfile)) }
            add(AccountMenuItem("Sign out", WebIcon.LogOut, onSignOut))
        }

    // .menu-anchor, not .f-wrap: the latter is the form-field wrapper and is width:100%, which
    // stretched this menu across the whole content area.
    Div(attrs = {
        classes("menu-anchor")
        ref { element ->
            anchor.element = element
            onDispose { anchor.element = null }
        }
        onKeyDown { event ->
            if (open && event.key == "Escape") {
                event.preventDefault()
                close(returnFocus = true)
            }
        }
    }) {
        Button(attrs = {
            classes("iconbtn")
            attr("type", "button")
            attr("title", "Account")
            attr("aria-label", "Account")
            attr("aria-haspopup", "menu")
            attr("aria-expanded", open.toString())
            ref { element ->
                trigger.element = element
                onDispose { trigger.element = null }
            }
            onClick { open = !open }
        }) {
            Icon(WebIcon.Shield, size = ICON_SIZE)
        }

        if (open) {
            Div(attrs = {
                classes("menu")
                attr("role", "menu")
                attr("aria-label", "Account")
            }) {
                items.forEachIndexed { index, item ->
                    // ⛔ A real <button role="menuitem">, like ActionsMenu's. These were clickable
                    // <div>s: Sign out and Your profile could not be reached from the keyboard at all.
                    Button(attrs = {
                        classes("menu-i")
                        attr("type", "button")
                        attr("role", "menuitem")
                        // Opening a menu from the keyboard puts the reader inside it, not back on the
                        // button they pressed to open it.
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
                        Icon(item.icon, size = ICON_SIZE)
                        Text(item.label)
                    }
                }
            }
        }
    }
}

private class AccountMenuItem(
    val label: String,
    val icon: WebIcon,
    val onSelect: () -> Unit,
)

/** A DOM node captured by `ref`, for the two places the menu has to reach past composition. */
private class ElementHolder {
    var element: HTMLElement? = null
}

private const val ICON_SIZE = 18
