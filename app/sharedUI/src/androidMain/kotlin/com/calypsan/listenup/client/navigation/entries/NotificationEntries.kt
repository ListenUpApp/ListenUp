package com.calypsan.listenup.client.navigation.entries

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.calypsan.listenup.client.data.repository.ShortcutAction
import com.calypsan.listenup.client.features.notifications.NotificationsScreen
import com.calypsan.listenup.client.navigation.Notifications

/**
 * Notification surface entries: the inbox list. Taps route their [ShortcutAction] through
 * [onAction] — the same dispatch the system shade uses, so the two entry points cannot disagree.
 */
internal fun EntryProviderScope<NavKey>.notificationEntries(
    backStack: NavBackStack<NavKey>,
    onAction: (ShortcutAction) -> Unit,
) {
    entry<Notifications> {
        NotificationsScreen(
            onNavigateBack = { backStack.removeAt(backStack.lastIndex) },
            onAction = onAction,
        )
    }
}
