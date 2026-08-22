@file:MustUseReturnValues

package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.dto.NotificationPreferenceDto
import com.calypsan.listenup.api.notifications.NotificationPreference
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.AppNotification
import kotlinx.coroutines.flow.Flow

/** The notification inbox: Room-backed reads, outbox-backed read-state, RPC preferences. */
interface NotificationRepository {
    /** Live inbox, newest first. Offline-correct — reads Room, never the network. */
    fun observeNotifications(): Flow<List<AppNotification>>

    /** Live unread count — the bell badge. Derives from the same table as the list. */
    fun observeUnreadCount(): Flow<Int>

    /** Marks a notification read: Room immediately, server via the outbox. */
    suspend fun markRead(notificationId: String): AppResult<Unit>

    /** Per-type delivery preferences (online — server-resolved against defaults). */
    suspend fun getPreferences(): AppResult<List<NotificationPreferenceDto>>

    /** Overrides one type's preference (online). */
    suspend fun updatePreference(
        type: String,
        preference: NotificationPreference,
    ): AppResult<Unit>
}
