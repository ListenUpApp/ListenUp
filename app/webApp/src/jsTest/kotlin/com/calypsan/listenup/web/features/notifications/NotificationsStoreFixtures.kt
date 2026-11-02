package com.calypsan.listenup.web.features.notifications

import com.calypsan.listenup.api.notifications.NotificationPreference
import com.calypsan.listenup.client.presentation.notifications.NotificationPrefsUiState
import com.calypsan.listenup.client.presentation.notifications.NotificationsUiState
import kotlinx.coroutines.flow.MutableStateFlow

/** A session over a state that never changes — the shape specs use in place of the graph. */
fun fixedNotifications(
    state: NotificationsUiState,
    onMarkRead: (String) -> Unit = {},
): OpenNotifications = { NotificationsSession(MutableStateFlow(state), onMarkRead, close = {}) }

/** A badge over a count that never changes. */
fun fixedNotificationBell(unreadCount: Int = 0): OpenNotificationBell =
    { NotificationBellSession(MutableStateFlow(unreadCount), close = {}) }

/**
 * Preferences over a fixed state.
 *
 * [onSetPreference] records but does not move the state: the real ViewModel applies a toggle
 * optimistically and may revert it, and a fixture that flipped the switch itself would let a page
 * that ignores its state entirely still look right.
 */
fun fixedNotificationPrefs(
    state: NotificationPrefsUiState,
    onSetPreference: (String, NotificationPreference) -> Unit = { _, _ -> },
    onRetry: () -> Unit = {},
): OpenNotificationPrefs = { NotificationPrefsSession(MutableStateFlow(state), onSetPreference, onRetry, close = {}) }
