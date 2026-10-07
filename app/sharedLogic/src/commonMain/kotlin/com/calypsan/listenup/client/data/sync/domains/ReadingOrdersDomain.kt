package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.ReadingOrderSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.ReadingOrderEntity

/**
 * The `reading_orders` domain (#962): library-wide — every user receives every order, like series — so
 * no [AccessGate]; an order whose books the viewer can't see simply shows no books. Server-wins apply,
 * soft tombstones, full digest, outbox-backed writes.
 *
 * **Outbox writes, create included.** Unlike shelves and collections, an order is created offline: the
 * client mints its id, so create, rename and delete all queue on [OutboxChannels.ReadingOrders] keyed by
 * the order id, and the in-flight shield holds back the order's own echo until its op drains.
 */
internal fun readingOrdersDomain(database: ListenUpDatabase): MirroredDomain<ReadingOrderSyncPayload> {
    val apply = ReadingOrderMirrorApply(database)
    return MirroredDomain(
        key = SyncDomains.READING_ORDERS,
        apply = apply,
        conflict = ConflictPolicy.ServerWins(RevisionGuard { id -> database.readingOrderDao().revisionOf(id) }),
        deletes = DeleteSemantics.SoftDelete(apply::tombstoneById),
        digest = fullDigest(database.readingOrderDao()::digestRows),
        writes = WriteTier.Outbox(OutboxChannels.ReadingOrders),
    )
}

/** Room mapping for [ReadingOrderSyncPayload]. */
internal class ReadingOrderMirrorApply(
    private val database: ListenUpDatabase,
) : MirrorApply<ReadingOrderSyncPayload> {
    override suspend fun upsert(payload: ReadingOrderSyncPayload) {
        database.readingOrderDao().upsert(
            ReadingOrderEntity(
                id = payload.id,
                seriesId = payload.seriesId,
                name = payload.name,
                createdBy = payload.createdBy,
                revision = payload.revision,
                createdAt = payload.createdAt,
                updatedAt = payload.updatedAt,
                deletedAt = payload.deletedAt,
            ),
        )
    }

    /** Tombstone from a `Deleted` frame. */
    suspend fun tombstoneById(
        id: String,
        deletedAt: Long,
        revision: Long,
    ) {
        database.readingOrderDao().softDelete(id = id, deletedAt = deletedAt, revision = revision)
    }

    override suspend fun tombstoneFromItem(item: ReadingOrderSyncPayload) {
        tombstoneById(id = item.id, deletedAt = item.deletedAt ?: item.updatedAt, revision = item.revision)
    }
}
