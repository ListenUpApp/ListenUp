package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.NotificationPreferenceDto
import com.calypsan.listenup.api.notifications.NotificationPreference
import com.calypsan.listenup.api.result.AppResult
import kotlinx.rpc.annotations.Rpc

/**
 * The notifications RPC surface. Rows travel via the `notifications` sync domain (firehose +
 * cursored pull), NOT through this service — it carries only the writes and the preference CRUD.
 */
@Rpc
interface NotificationService {
    /** Marks one of the caller's notifications read. Idempotent; NotFound for another user's row. */
    suspend fun markRead(notificationId: String): AppResult<Unit>

    /** Every notification type with the caller's resolved preference (stored override or default). */
    suspend fun getPreferences(): AppResult<List<NotificationPreferenceDto>>

    /** Overrides the caller's preference for one type. Fails on an unknown type discriminator. */
    suspend fun updatePreference(
        type: String,
        preference: NotificationPreference,
    ): AppResult<Unit>
}
