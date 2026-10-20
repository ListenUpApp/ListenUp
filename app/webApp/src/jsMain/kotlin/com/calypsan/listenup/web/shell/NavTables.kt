package com.calypsan.listenup.web.shell

import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.features.search.label
import com.calypsan.listenup.web.nav.ADMIN_KEY
import com.calypsan.listenup.web.nav.DISCOVER_KEY
import com.calypsan.listenup.web.nav.HOME_KEY
import com.calypsan.listenup.web.nav.LIBRARY_KEY
import com.calypsan.listenup.web.nav.NOTIFICATIONS_KEY
import com.calypsan.listenup.web.nav.SETTINGS_KEY

/**
 * The primary destinations, with the held-for-review count on Library.
 *
 * A function rather than a constant for the reason [footerNav] is one: the badge moves, and a `val`
 * could only ever show the count the app started with.
 */
internal fun primaryNav(heldCount: Int): NavSection =
    NavSection(
        entries =
            listOf(
                NavEntry(HOME_KEY, "Home", WebIcon.Home, href = "/"),
                NavEntry(LIBRARY_KEY, "Library", WebIcon.Book, badge = heldCount, badgeKind = NavBadgeKind.Held),
                NavEntry(DISCOVER_KEY, "Discover", WebIcon.Compass),
                // The phone's header carries Search, so its tab bar is Home · Library · Discover.
                NavEntry("search", "Search", WebIcon.Search, onPhone = false),
            ),
    )

private val FOOTER_NAV =
    listOf(
        NavEntry(NOTIFICATIONS_KEY, "Notifications", WebIcon.Bell),
        NavEntry(ADMIN_KEY, "Admin", WebIcon.Shield),
        NavEntry(SETTINGS_KEY, "Settings", WebIcon.Cog),
    )

/**
 * The footer entries this account actually has, with the live unread count on the bell.
 *
 * Built per render rather than held as a constant because the badge moves: a `val` list could only
 * ever show the count the app started with.
 */
internal fun footerNav(
    isAdmin: Boolean,
    unreadCount: Int,
): List<NavEntry> =
    FOOTER_NAV
        .filterNot { it.key == ADMIN_KEY && !isAdmin }
        .map {
            if (it.key ==
                NOTIFICATIONS_KEY
            ) {
                NavEntry(it.key, it.label, it.icon, badge = unreadCount, href = it.href)
            } else {
                it
            }
        }
