package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.ReadingOrderSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.ReadingOrderEntity

/**
 * The `reading_orders` domain: user-scoped own-data — the server already scopes
 * pull and firehose queries to the caller's rows, so no [AccessGate] is declared
 * (the shelves precedent). Server-wins apply (idempotent upsert absorbs own
 * echoes), soft-delete tombstones, full digest. Metadata edits write through the
 * outbox ([WriteTier.Outbox]) per the Integration Foundations §5.3 amendment;
 * create/delete stay direct RPC (create needs the server-minted id, deletes are
 * online by standing product decision).
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

/** Room mapping for [ReadingOrderSyncPayload] payloads. */
internal class ReadingOrderMirrorApply(
    private val database: ListenUpDatabase,
) : MirrorApply<ReadingOrderSyncPayload> {
    override suspend fun upsert(payload: ReadingOrderSyncPayload) {
        database.readingOrderDao().upsert(
            ReadingOrderEntity(
                id = payload.id,
                name = payload.name,
                description = payload.description,
                attribution = payload.attribution,
                isPrivate = payload.isPrivate,
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
        database.readingOrderDao().softDelete(id = id, deletedAt = deletedAt, revision = revision)
    }

    override suspend fun tombstoneFromItem(item: ReadingOrderSyncPayload) {
        tombstoneById(
            id = item.id,
            deletedAt = item.deletedAt ?: item.updatedAt,
            revision = item.revision,
        )
    }
}
