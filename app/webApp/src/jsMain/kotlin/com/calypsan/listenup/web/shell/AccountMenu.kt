package com.calypsan.listenup.web.shell

import com.calypsan.listenup.web.design.buttonClasses
import com.calypsan.listenup.web.design.ButtonSize
import com.calypsan.listenup.web.design.ButtonKind
import androidx.compose.runtime.Composable
import com.calypsan.listenup.web.design.MenuAction
import com.calypsan.listenup.web.design.PopupMenu
import com.calypsan.listenup.web.design.UserAvatar
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
    /** Who is signed in; null while not yet known. */
    account: AccountIdentity? = null,
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
        // The person, as Android's and iOS's headers draw them: their picture, or their initials.
        triggerContent =
            account?.let { who ->
                { UserAvatar(userId = who.userId, name = who.name, size = AVATAR_SIZE) }
            },
    )
}

/** The signed-in person, as the account menu's trigger draws them. */
data class AccountIdentity(
    val userId: String,
    val name: String,
)

/** The avatar inside the account trigger: inset within the 40px button, as on the native headers. */
private const val AVATAR_SIZE = 30
