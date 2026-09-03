package com.calypsan.listenup.web.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.WebIcon
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text

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

    // .menu-anchor, not .f-wrap: the latter is the form-field wrapper and is width:100%, which
    // stretched this menu across the whole content area.
    Div(attrs = { classes("menu-anchor") }) {
        Button(attrs = {
            classes("iconbtn")
            attr("type", "button")
            attr("title", "Account")
            onClick { open = !open }
        }) {
            Icon(WebIcon.Shield, size = ICON_SIZE)
        }

        if (open) {
            Div(attrs = { classes("menu") }) {
                onOpenProfile?.let { openProfile ->
                    Div(attrs = {
                        classes("menu-i")
                        onClick {
                            open = false
                            openProfile()
                        }
                    }) {
                        Icon(WebIcon.Person, size = ICON_SIZE)
                        Text("Your profile")
                    }
                }
                Div(attrs = {
                    classes("menu-i")
                    onClick {
                        open = false
                        onSignOut()
                    }
                }) {
                    Icon(WebIcon.LogOut, size = ICON_SIZE)
                    Text("Sign out")
                }
            }
        }
    }
}

private const val ICON_SIZE = 18
