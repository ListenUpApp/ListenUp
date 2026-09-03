package com.calypsan.listenup.web.features.notifications

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.domain.model.AppNotification
import com.calypsan.listenup.client.presentation.notifications.NotificationsUiState
import com.calypsan.listenup.client.util.relativeLastActive
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.WebIcon
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * The notification inbox — what happened while you were away, newest first.
 *
 * Pure in [state], the split every other web page makes: the store wiring lives one level up.
 *
 * **A row is a control even when it goes nowhere.** Every notification can be marked read, and
 * that is a real thing to do to it, so the whole row is a `<button>` rather than a div with a dot
 * you have to find. Where the notification also has a destination, the same press does both —
 * marking read and navigating are one gesture because a listener who has just followed a
 * notification has, by any reading, seen it.
 *
 * ⛔ **No "mark all read".** `NotificationsViewModel` has no such call and the repository has no
 * bulk endpoint behind it, so the button would either be a lie or a loop of N writes this page
 * invented. It belongs in the shared ViewModel first.
 */
@Composable
fun NotificationsPage(
    state: NotificationsUiState,
    nowMs: Long,
    onOpen: (AppNotification) -> Unit,
) {
    Div(attrs = { classes("ntf") }) {
        H1(attrs = { classes("ntf-title") }) { Text("Notifications") }

        when (state) {
            is NotificationsUiState.Data -> {
                Div(attrs = { classes("ntf-list") }) {
                    state.notifications.forEach { notification ->
                        NotificationRow(notification, nowMs, onOpen)
                    }
                }
            }

            NotificationsUiState.Empty -> {
                Div(attrs = { classes("empty") }) {
                    H3 { Text("Nothing waiting") }
                    P { Text("Invitations and account news land here.") }
                }
            }

            NotificationsUiState.Loading -> {
                Div(attrs = { classes("skel", "ntf-skel") })
            }
        }
    }
}

/**
 * One notification: what it is, what it says, when it arrived, and whether you have seen it.
 *
 * The unread mark is a dot AND the row's own weight, not colour alone — a single coral dot is the
 * kind of distinction that disappears for a reader who cannot separate it from the surface behind
 * it. `aria-label` carries "unread" in words for the same reason.
 */
@Composable
private fun NotificationRow(
    notification: AppNotification,
    nowMs: Long,
    onOpen: (AppNotification) -> Unit,
) {
    val copy = notificationCopy(notification.event)
    Button(attrs = {
        classes("ntf-row")
        if (notification.isUnread) classes("unread")
        attr("type", "button")
        attr("aria-label", if (notification.isUnread) "${copy.title}, unread" else copy.title)
        onClick { onOpen(notification) }
    }) {
        Span(attrs = { classes("ntf-dot") })
        Div(attrs = { classes("ntf-text") }) {
            Div(attrs = { classes("ntf-t") }) { Text(copy.title) }
            Div(attrs = { classes("ntf-b") }) { Text(copy.body) }
        }
        Span(attrs = { classes("ntf-when") }) { Text(relativeLastActive(notification.createdAt, nowMs)) }
        Icon(WebIcon.ChevronRight, size = ROW_CHEVRON_SIZE)
    }
}

private const val ROW_CHEVRON_SIZE = 16
