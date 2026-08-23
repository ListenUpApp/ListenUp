package com.calypsan.listenup.client.presentation.notifications

import com.calypsan.listenup.api.notifications.NotificationEvent
import com.calypsan.listenup.api.notifications.NotificationTarget
import com.calypsan.listenup.client.data.repository.ShortcutAction
import com.calypsan.listenup.client.domain.model.AppNotification

/**
 * THE tap mapping — the single place a notification's [NotificationTarget] becomes navigation.
 * Both entry points call it (the in-app list, and the system shade via
 * [com.calypsan.listenup.api.push.PushPayload]'s reverse projection), which is what stops them
 * disagreeing. Null means "open the app, nothing more" — the target says so ([NotificationTarget.None]),
 * the type is unknown to this build, or the destination's surface does not exist yet.
 */
fun AppNotification.toShortcutAction(): ShortcutAction? = event?.toShortcutAction()

/** Event-level overload for the shade path, where there is no [AppNotification] row. */
fun NotificationEvent.toShortcutAction(): ShortcutAction? = target.toShortcutAction(eventForContext = this)

/**
 * The target-level `when` both public entry points share. Internal so tests can pin every
 * [NotificationTarget] case directly — no shipped event carries a Book or Profile target yet.
 * [eventForContext] supplies event fields where a destination needs an id beyond the target's own.
 */
internal fun NotificationTarget.toShortcutAction(eventForContext: NotificationEvent?): ShortcutAction? =
    when (this) {
        is NotificationTarget.Book -> {
            ShortcutAction.NavigateToBook(bookId)
        }

        is NotificationTarget.Profile -> {
            ShortcutAction.NavigateToUserProfile(userId)
        }

        // No campfire surface exists yet (#1065 — CampfireInvite is declared but never sent).
        // When one ships, this branch is the conscious edit that wires it.
        is NotificationTarget.Campfire -> {
            null
        }

        is NotificationTarget.AdminInbox -> {
            (eventForContext as? NotificationEvent.RegistrationApproval)
                ?.let { ShortcutAction.NavigateToPendingApprovals(it.userId) }
        }

        is NotificationTarget.None -> {
            null
        }
    }
