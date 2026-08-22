package com.calypsan.listenup.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Outbox mutation for the `notifications` domain — what a queued client op carries. */
@Serializable
sealed interface NotificationMutation {
    /** Mark one notification read (idempotent; last-write-wins on the server row). */
    @Serializable
    @SerialName("NotificationMutation.MarkRead")
    data class MarkRead(
        @SerialName("notificationId") val notificationId: String,
    ) : NotificationMutation
}
