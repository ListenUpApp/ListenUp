package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.api.notifications.NotificationEvent

/**
 * One inbox notification as the UI consumes it. [event] is null when this build does not know the
 * type — the UI MUST render those generically, never drop them.
 */
data class AppNotification(
    val id: String,
    /** The wire discriminator, kept even when [event] is null so a generic row can still say what it is. */
    val type: String,
    /** The typed event, or null for a type this build does not know. */
    val event: NotificationEvent?,
    /** Epoch-ms the notification was minted (server clock) — the inbox sort key. */
    val createdAt: Long,
    /** Epoch-ms the user read it; null while unread. */
    val readAt: Long?,
) {
    /** Whether this notification counts toward the bell badge. */
    val isUnread: Boolean get() = readAt == null
}
