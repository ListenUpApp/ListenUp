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
