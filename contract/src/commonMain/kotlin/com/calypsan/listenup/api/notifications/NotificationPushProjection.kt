package com.calypsan.listenup.api.notifications

import com.calypsan.listenup.api.push.PushPayload

/**
 * Projects a [NotificationEvent] onto the push wire format. [PushPayload] is a projection of the
 * push-eligible subset, not a parallel hierarchy — this exhaustive `when` is the ONLY mapping, so a
 * new push-eligible case is a compile error here until projected. Returns null for cases that never
 * push (none yet in Slice 1; ambient Slice-2 types will be).
 */
fun NotificationEvent.toPushPayload(): PushPayload? =
    when (this) {
        is NotificationEvent.CampfireInvite -> {
            PushPayload.CampfireInvite(campfireId = campfireId, bookId = bookId, inviterUserId = inviterUserId)
        }

        is NotificationEvent.RegistrationDecision -> {
            PushPayload.RegistrationDecision(userId = userId, approved = approved)
        }

        is NotificationEvent.RegistrationApproval -> {
            PushPayload.RegistrationApproval(userId = userId)
        }
    }

/**
 * Inverts [toPushPayload]: recovers the typed [NotificationEvent] a push was projected from, or
 * null for pushes that are not notifications ([PushPayload.TestNotification] — a diagnostic).
 * The shade tap and the in-app list route through the same target mapping because both start here.
 */
fun PushPayload.toNotificationEvent(): NotificationEvent? =
    when (this) {
        is PushPayload.CampfireInvite -> {
            NotificationEvent.CampfireInvite(campfireId = campfireId, bookId = bookId, inviterUserId = inviterUserId)
        }

        is PushPayload.RegistrationDecision -> {
            NotificationEvent.RegistrationDecision(userId = userId, approved = approved)
        }

        is PushPayload.RegistrationApproval -> {
            NotificationEvent.RegistrationApproval(userId = userId)
        }

        is PushPayload.TestNotification -> {
            null
        }
    }
