package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text

/** One entry in an [ActionsMenu]. */
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

    var open by remember { mutableStateOf(false) }

    Div(attrs = { classes("menu-anchor") }) {
        Button(attrs = {
            classes("btn-sq")
            attr("type", "button")
            attr("aria-label", label)
            attr("aria-expanded", open.toString())
            if (!enabled) attr("disabled", "")
            onClick { open = !open }
        }) { Icon(WebIcon.Grip, size = MENU_ICON_SIZE) }

        if (open) {
            Div(attrs = {
                classes("menu")
                attr("role", "menu")
            }) {
                items.forEach { item ->
                    // ⛔ A real <button role="menuitem">, not the clickable <div> the account menu
                    // uses: a div is unreachable by keyboard and announces nothing. Same CSS, so it
                    // looks identical; the account menu wants the same treatment separately.
                    Button(attrs = {
                        classes("menu-i")
                        attr("type", "button")
                        attr("role", "menuitem")
                        onClick {
                            open = false
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

private const val MENU_ICON_SIZE = 18
