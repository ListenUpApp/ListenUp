package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.NotificationService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.NotificationMutation
import com.calypsan.listenup.api.dto.NotificationPreferenceDto
import com.calypsan.listenup.api.notifications.NotificationEvent
import com.calypsan.listenup.api.notifications.NotificationPreference
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.NotificationDao
import com.calypsan.listenup.client.data.local.db.NotificationEntity
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.client.domain.model.AppNotification
import com.calypsan.listenup.client.domain.repository.NotificationRepository
import com.calypsan.listenup.core.currentEpochMilliseconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException

/**
 * Production implementation of [NotificationRepository].
 *
 * **Observation** (Room-backed, offline-first): the inbox list and the unread count read from
 * Room; the sync engine writes server-committed rows via
 * [com.calypsan.listenup.client.data.sync.domains.notificationsDomain], so the UI reacts without
 * polling. Each row's stored event JSON decodes lazily at read time — an unknown type maps to
 * [AppNotification] with `event == null`, which the UI renders generically, never drops.
 *
 * **Mutation**: `markRead` is offline-first — the read stamp writes Room optimistically and a
 * durable [NotificationMutation.MarkRead] op queues on [OutboxChannels.Notifications] (via
 * [OfflineEditor.edit]); the server's echo reconciles through the sync domain. The preference
 * surface stays online — preferences are server-resolved against per-type defaults and are not
 * mirrored in Room.
 */
internal class NotificationRepositoryImpl(
    private val channel: RpcChannel<NotificationService>,
    private val notificationDao: NotificationDao,
    private val offlineEditor: OfflineEditor,
) : NotificationRepository {
    override fun observeNotifications(): Flow<List<AppNotification>> =
        notificationDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override fun observeUnreadCount(): Flow<Int> = notificationDao.observeUnreadCount()

    override suspend fun markRead(notificationId: String): AppResult<Unit> =
        offlineEditor.edit(
            OutboxChannels.Notifications,
            notificationId,
            NotificationMutation.MarkRead(notificationId = notificationId),
        ) {
            notificationDao.markRead(id = notificationId, readAt = currentEpochMilliseconds())
        }

    override suspend fun getPreferences(): AppResult<List<NotificationPreferenceDto>> =
        channel.call(idempotent = true) { it.getPreferences() }

    override suspend fun updatePreference(
        type: String,
        preference: NotificationPreference,
    ): AppResult<Unit> = channel.call { it.updatePreference(type, preference) }
}

// ── Mapping ───────────────────────────────────────────────────────────────────

/**
 * Map a Room [NotificationEntity] to the domain [AppNotification], decoding the stored event JSON
 * lazily. A type this build does not know (or a body its `init` validation rejects) maps to
 * `event == null` — the generic-rendering contract, mirroring
 * [com.calypsan.listenup.api.sync.decodeEvent].
 */
private fun NotificationEntity.toDomain(): AppNotification =
    AppNotification(
        id = id,
        type = type,
        event =
            try {
                contractJson.decodeFromString(NotificationEvent.serializer(), eventJson)
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                // Not redundant with the clause above (SerializationException extends IAE): this one
                // catches `init`-block `require` validation on future event cases — the contract
                // convention — which throws plain IllegalArgumentException.
                null
            },
        createdAt = createdAt,
        readAt = readAt,
    )
