package com.calypsan.listenup.web.features.notifications

import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.api.notifications.NotificationPreference
import com.calypsan.listenup.client.presentation.notifications.NotificationBellViewModel
import com.calypsan.listenup.client.presentation.notifications.NotificationPrefsUiState
import com.calypsan.listenup.client.presentation.notifications.NotificationPrefsViewModel
import com.calypsan.listenup.client.presentation.notifications.NotificationsUiState
import com.calypsan.listenup.client.presentation.notifications.NotificationsViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.Koin

/**
 * An open notification inbox, the one gesture it accepts, and the teardown for it.
 *
 * The same arrangement every other web page makes: a browser has no `ViewModelStore` to hand a
 * lifetime to, so the composition owns one.
 */
class NotificationsSession(
    val state: StateFlow<NotificationsUiState>,
    val onMarkRead: (String) -> Unit,
    val close: () -> Unit,
)

/**
 * The unread count, and the teardown for it.
 *
 * Separate from [NotificationsSession] and deliberately longer-lived: the badge is on the shell,
 * so it is open for as long as the app is, while the inbox opens and closes with its route. Folding
 * them together would either keep the whole inbox query subscribed forever or blank the badge the
 * moment you navigated away from the page that proves it was right.
 */
class NotificationBellSession(
    val unreadCount: StateFlow<Int>,
    val close: () -> Unit,
)

/**
 * An open preferences screen, the two gestures it accepts, and the teardown for it.
 *
 * Its own session rather than a field on [NotificationsSession] because the two have nothing in
 * common but a repository: the inbox is a Room observation, and preferences are a server round
 * trip that reloads on retry. Sharing a lifetime would keep one subscribed for the other's sake.
 */
class NotificationPrefsSession(
    val state: StateFlow<NotificationPrefsUiState>,
    val onSetPreference: (String, NotificationPreference) -> Unit,
    val onRetry: () -> Unit,
    val close: () -> Unit,
)

/** How the preferences page gets its state. */
typealias OpenNotificationPrefs = () -> NotificationPrefsSession

/** How the page gets its inbox. Production resolves the real ViewModel; specs hand over a state. */
typealias OpenNotifications = () -> NotificationsSession

/** How the shell gets its unread count. */
typealias OpenNotificationBell = () -> NotificationBellSession

/**
 * The production inbox: the shared [NotificationsViewModel], resolved from the started Koin graph.
 *
 * The ViewModel goes into a [ViewModelStore] of its own for the same reason
 * [com.calypsan.listenup.web.features.bookdetail.graphBookDetail] does — clearing the store is the
 * only sanctioned way to end a `viewModelScope`.
 */
fun graphNotifications(koin: Koin): OpenNotifications =
    {
        val viewModel = koin.get<NotificationsViewModel>()
        val store = ViewModelStore().apply { put("notifications", viewModel) }
        NotificationsSession(
            state = viewModel.uiState,
            onMarkRead = viewModel::markRead,
            close = store::clear,
        )
    }

/** The production badge: the shared [NotificationBellViewModel] over the same Room table. */
fun graphNotificationBell(koin: Koin): OpenNotificationBell =
    {
        val viewModel = koin.get<NotificationBellViewModel>()
        val store = ViewModelStore().apply { put("notification-bell", viewModel) }
        NotificationBellSession(unreadCount = viewModel.unreadCount, close = store::clear)
    }

/**
 * The production preferences screen: the shared [NotificationPrefsViewModel].
 *
 * That ViewModel loads in its own `init`, so resolving it IS the first fetch — there is no separate
 * "start" call to forget.
 */
fun graphNotificationPrefs(koin: Koin): OpenNotificationPrefs =
    {
        val viewModel = koin.get<NotificationPrefsViewModel>()
        val store = ViewModelStore().apply { put("notification-prefs", viewModel) }
        NotificationPrefsSession(
            state = viewModel.uiState,
            onSetPreference = viewModel::setPreference,
            onRetry = viewModel::refresh,
            close = store::clear,
        )
    }

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
