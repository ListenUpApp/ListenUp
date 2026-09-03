package com.calypsan.listenup.web.features.notifications

import com.calypsan.listenup.api.notifications.NotificationEvent

/** What one inbox row says: a headline and a sentence under it. */
class NotificationCopy(
    val title: String,
    val body: String,
)

/**
 * The words for [event].
 *
 * ⛔ **This is a second copy of a decision `:app:sharedUI` also makes** — `notificationItemCopyRes`
 * resolves the same four cases to `StringResource`s. The duplication is not an oversight and not
 * fixable by importing: those are Compose Multiplatform resources, `:app:webApp` depends on
 * `:app:sharedLogic` only, and Compose HTML has no resource loader. Every user-facing string on
 * web is written in place for the same reason, so this is the web pattern rather than an exception
 * to it — but the two must be read together when the wording changes.
 *
 * The strings are the sharedUI ones verbatim, and `NotificationCopyTest` pins them, so a divergence
 * shows up as a failing assertion naming both sides rather than as two apps quietly disagreeing.
 *
 * A null [event] is a type this build does not know — an older web client against a newer server.
 * It renders the generic copy rather than dropping the row: an unreadable notification is still
 * evidence that something happened, and hiding it would make the badge count things nobody can see.
 */
fun notificationCopy(event: NotificationEvent?): NotificationCopy =
    when (event) {
        null -> {
            NotificationCopy(
                title = "Notification",
                body = "Update ListenUp to see this notification.",
            )
        }

        is NotificationEvent.CampfireInvite -> {
            NotificationCopy(
                title = "Campfire invite",
                body = "You've been invited to listen together.",
            )
        }

        is NotificationEvent.RegistrationDecision -> {
            if (event.approved) {
                NotificationCopy(
                    title = "You're in",
                    body = "Your registration was approved. Welcome to ListenUp.",
                )
            } else {
                NotificationCopy(
                    title = "Registration declined",
                    body = "An admin declined your registration.",
                )
            }
        }

        is NotificationEvent.RegistrationApproval -> {
            NotificationCopy(
                title = "Registration waiting",
                body = "Someone is waiting for approval to join your server.",
            )
        }
    }
