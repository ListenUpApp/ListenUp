package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.ReadingOrderFollowSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.ReadingOrderFollowEntity

/**
 * The `reading_order_follows` domain (#962): the signed-in user's choice of order per series. User-scoped
 * own-data — the server sends only the caller's rows — so no [AccessGate]. Server-wins apply, soft
 * tombstones (a tombstone means "inherit"), full digest, outbox-backed writes keyed by the follow id
 * "<userId>:<seriesId>", under one op kind so a run of choices on one series coalesces to the last.
 */
internal fun readingOrderFollowsDomain(database: ListenUpDatabase): MirroredDomain<ReadingOrderFollowSyncPayload> {
    val apply = ReadingOrderFollowMirrorApply(database)
    return MirroredDomain(
        key = SyncDomains.READING_ORDER_FOLLOWS,
        apply = apply,
        conflict =
            ConflictPolicy.ServerWins(RevisionGuard { id -> database.readingOrderFollowDao().revisionOf(id) }),
        deletes = DeleteSemantics.SoftDelete(apply::tombstoneById),
        digest = fullDigest(database.readingOrderFollowDao()::digestRows),
        writes = WriteTier.Outbox(OutboxChannels.ReadingOrderFollows),
    )
}

/** Room mapping for [ReadingOrderFollowSyncPayload]. */
internal class ReadingOrderFollowMirrorApply(
    private val database: ListenUpDatabase,
) : MirrorApply<ReadingOrderFollowSyncPayload> {
    override suspend fun upsert(payload: ReadingOrderFollowSyncPayload) {
        database.readingOrderFollowDao().upsert(
            ReadingOrderFollowEntity(
                id = payload.id,
                seriesId = payload.seriesId,
                choice = payload.choice.name,
                readingOrderId = payload.readingOrderId,
                revision = payload.revision,
                createdAt = payload.createdAt,
                updatedAt = payload.updatedAt,
                deletedAt = payload.deletedAt,
            ),
        )
    }

    /** Tombstone from a `Deleted` frame — the series inherits again. */
    suspend fun tombstoneById(
        id: String,
        deletedAt: Long,
        revision: Long,
    ) {
        database.readingOrderFollowDao().softDelete(id = id, deletedAt = deletedAt, revision = revision)
    }

    override suspend fun tombstoneFromItem(item: ReadingOrderFollowSyncPayload) {
        tombstoneById(id = item.id, deletedAt = item.deletedAt ?: item.updatedAt, revision = item.revision)
    }
}
