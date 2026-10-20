package com.calypsan.listenup.web.shell

import com.calypsan.listenup.web.design.buttonClasses
import com.calypsan.listenup.web.design.ButtonSize
import com.calypsan.listenup.web.design.ButtonKind
import androidx.compose.runtime.Composable
import com.calypsan.listenup.web.design.MenuAction
import com.calypsan.listenup.web.design.PopupMenu
import com.calypsan.listenup.web.design.WebIcon

/**
 * The way out.
 *
 * Deliberately small: account management belongs on Settings, and this exists so the auth arc is a
 * loop rather than a one-way door — without it, seeing the login screen a second time means
 * clearing `localStorage` by hand. The keyboard contract is [PopupMenu]'s, shared with every
 * "more actions" menu in the app.
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
    /**
     * Destinations the phone's header folds in here, between the profile and Sign out: the sidebar's footer entries
     * (Admin, Settings), which have no tab of their own below 760px. Empty everywhere the sidebar shows them.
     */
    destinations: List<MenuAction> = emptyList(),
    /** The trigger's classes: the phone header draws it as one of its 44px buttons. */
    triggerClasses: Array<String> = buttonClasses(ButtonKind.Icon, ButtonSize.Sm),
) {
    val items =
        buildList {
            onOpenProfile?.let { openProfile -> add(MenuAction("Your profile", WebIcon.Person, openProfile)) }
            addAll(destinations)
            add(MenuAction("Sign out", WebIcon.LogOut, onSignOut))
        }

    PopupMenu(
        items = items,
        label = "Account",
        icon = WebIcon.Shield,
        triggerClasses = triggerClasses,
        tooltip = true,
    )
}
