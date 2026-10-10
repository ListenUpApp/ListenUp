package com.calypsan.listenup.web.features.notifications

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import com.calypsan.listenup.client.data.repository.ShortcutAction
import com.calypsan.listenup.client.presentation.notifications.toShortcutAction
import com.calypsan.listenup.core.currentEpochMilliseconds
import com.calypsan.listenup.web.features.settings.DevicesRoute
import com.calypsan.listenup.web.nav.Router
import com.calypsan.listenup.web.nav.Routes
import com.calypsan.listenup.web.nav.rememberSession
import com.calypsan.listenup.web.shell.nowMs

/**
 * The `/notifications` branch — the inbox, and where a tap on one of its rows lands.
 *
 * `nowMs` is read once per visit rather than ticking, as on Discover and Admin: every timestamp
 * here is at least a minute old, so a live clock would repaint the list to say the same thing.
 *
 * ⛔ **Marking read happens on every press, navigation only sometimes.** The destination comes from
 * `toShortcutAction()` — THE shared tap mapping, so the browser cannot disagree with the phone
 * about where a notification goes — and two of its outcomes have no web surface: a user profile
 * (web has no profile page) and a campfire (no client has one). Those rows still mark themselves
 * read, because the listener did read them; they simply do not move. A row that refused to
 * acknowledge a press because its destination is unbuilt would look broken rather than incomplete.
 */
@Composable
internal fun NotificationsRoute(
    router: Router,
    openNotifications: OpenNotifications,
) {
    val session = rememberSession { openNotifications() }
    val nowMs = remember { currentEpochMilliseconds() }

    NotificationsPage(
        state = session.state.collectAsState().value,
        nowMs = nowMs,
        onOpen = { notification ->
            session.onMarkRead(notification.id)
            when (val action = notification.toShortcutAction()) {
                is ShortcutAction.NavigateToBook -> {
                    router.navigate(Routes.book(action.bookId))
                }

                // Web's Admin page IS the pending-approvals surface, so this one lands — on the
                // section itself, since the request sits below the fold.
                is ShortcutAction.NavigateToPendingApprovals -> {
                    router.navigate(Routes.adminPending)
                }

                is ShortcutAction.NavigateToUserProfile -> {
                    router.navigate(Routes.profile(action.userId))
                }

                else -> {
                    Unit
                }
            }
        },
    )
}

/**
 * `/settings/notifications` — which notifications reach you, and how.
 *
 * A sub-route of Settings rather than a section inside it, the same call [DevicesRoute] makes: the
 * rows are a server round trip with their own loading and failure states, and folding those in
 * would give a page of instant local preferences a spinner it does not need.
 */
@Composable
internal fun NotificationPrefsRoute(
    router: Router,
    openNotificationPrefs: OpenNotificationPrefs,
) {
    val session = rememberSession { openNotificationPrefs() }

    NotificationPrefsPage(
        state = session.state.collectAsState().value,
        onSetPreference = session.onSetPreference,
        onRetry = session.onRetry,
        onOpenSettings = { router.navigate(Routes.settings) },
    )
}
