package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.NotificationSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.NotificationEntity
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * The `notifications` per-user inbox domain (Notifications — Room v7). Server-wins apply (read
 * state is last-write-wins on the server row), soft tombstones (retention pruning arrives as
 * ordinary deletes), full digest, outbox-backed writes (markRead). User-scoped own-data — no
 * [AccessGate], same as shelves: the server only ever sends the signed-in user's rows.
 *
 * The event body is stored verbatim: an unknown future type syncs and persists untouched and the
 * UI renders it generically (PushPayload's forward-compat rule, applied to the inbox). The domain
 * never decodes [NotificationSyncPayload.body] — decoding is the read path's concern.
 */
internal fun notificationsDomain(database: ListenUpDatabase): MirroredDomain<NotificationSyncPayload> {
    val apply = NotificationMirrorApply(database)
    return MirroredDomain(
        key = SyncDomains.NOTIFICATIONS,
        apply = apply,
        conflict = ConflictPolicy.ServerWins(RevisionGuard { id -> database.notificationDao().revisionOf(id) }),
        deletes = DeleteSemantics.SoftDelete(apply::tombstoneById),
        digest = fullDigest(database.notificationDao()::digestRows),
        writes = WriteTier.Outbox(OutboxChannels.Notifications),
    )
}

/** Room mapping for [NotificationSyncPayload]. */
internal class NotificationMirrorApply(
    private val database: ListenUpDatabase,
) : MirrorApply<NotificationSyncPayload> {
    override suspend fun upsert(payload: NotificationSyncPayload) {
        database.notificationDao().upsert(
            NotificationEntity(
                id = payload.id,
                type = payload.type,
                eventJson = payload.body,
                createdAt = payload.createdAt,
                updatedAt = payload.updatedAt,
                readAt = payload.readAt,
                revision = payload.revision,
                deletedAt = payload.deletedAt,
            ),
        )
    }

    /**
     * Tombstone from a firehose `Deleted` frame — a graceful no-op if [id] matches no local row
     * (nothing to reconcile locally). The event's own [revision] is written (not `revision + 1`)
     * so a replay is a no-op.
     */
    suspend fun tombstoneById(
        id: String,
        deletedAt: Long,
        revision: Long,
    ) {
        val affected = database.notificationDao().tombstone(id = id, deletedAt = deletedAt, revision = revision)
        if (affected == 0) {
            logger.debug { "notifications Deleted event matched no local row for id='$id' — graceful no-op" }
        }
    }

    override suspend fun tombstoneFromItem(item: NotificationSyncPayload) {
        tombstoneById(
            id = item.id,
            deletedAt = item.deletedAt ?: item.createdAt,
            revision = item.revision,
        )
    }
}
