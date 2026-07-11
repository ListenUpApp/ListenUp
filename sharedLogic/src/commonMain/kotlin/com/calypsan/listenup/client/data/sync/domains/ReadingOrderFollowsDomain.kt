package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.ReadingOrderFollowSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.ReadingOrderFollowEntity

/**
 * The `reading_order_follows` domain (Integration Foundations §5.4): the user's
 * per-series active reading order — the spoiler clock's pointer. User-scoped
 * own-data, server-wins apply, soft tombstones, full digest (the deterministic
 * `"$userId:$seriesId"` id is shared with the server, so — unlike playback
 * positions — the digest keys agree and no opt-out is needed). Setter writes
 * through the outbox ([WriteTier.Outbox]).
 */
internal fun readingOrderFollowsDomain(database: ListenUpDatabase): MirroredDomain<ReadingOrderFollowSyncPayload> {
    val apply = ReadingOrderFollowMirrorApply(database)
    return MirroredDomain(
        key = SyncDomains.READING_ORDER_FOLLOWS,
        apply = apply,
        conflict = ConflictPolicy.ServerWins(RevisionGuard { id -> database.readingOrderFollowDao().revisionOf(id) }),
        deletes = DeleteSemantics.SoftDelete(apply::tombstoneById),
        digest = fullDigest(database.readingOrderFollowDao()::digestRows),
        writes = WriteTier.Outbox(OutboxChannels.ReadingOrderFollows),
    )
}

/** Room mapping for [ReadingOrderFollowSyncPayload] payloads. */
internal class ReadingOrderFollowMirrorApply(
    private val database: ListenUpDatabase,
) : MirrorApply<ReadingOrderFollowSyncPayload> {
    override suspend fun upsert(payload: ReadingOrderFollowSyncPayload) {
        database.readingOrderFollowDao().upsert(
            ReadingOrderFollowEntity(
                id = payload.id,
                seriesId = payload.seriesId,
                activeReadingOrderId = payload.activeReadingOrderId,
                revision = payload.revision,
                deletedAt = payload.deletedAt,
                updatedAt = payload.updatedAt,
                createdAt = payload.createdAt,
            ),
        )
    }

    suspend fun tombstoneById(
        id: String,
        deletedAt: Long,
        revision: Long,
    ) {
        database.readingOrderFollowDao().softDelete(id = id, deletedAt = deletedAt, revision = revision)
    }

    override suspend fun tombstoneFromItem(item: ReadingOrderFollowSyncPayload) {
        tombstoneById(
            id = item.id,
            deletedAt = item.deletedAt ?: item.updatedAt,
            revision = item.revision,
        )
    }
}
