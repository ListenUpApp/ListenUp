package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.ReadingOrderBookSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.ReadingOrderBookEntity

/**
 * The `reading_order_books` junction domain: the synthetic
 * `"$readingOrderId:$bookId"` envelope id IS the local primary key, so events
 * apply by id alone — no composite parsing. Server-wins apply, soft tombstones,
 * full digest. Membership mutations (add/remove/reorder) write through the outbox
 * ([WriteTier.Outbox]) per the Integration Foundations §5.3 amendment — the
 * per-item-CRUD divergence from `shelf_books`' online-only posture.
 *
 * Junction rule: tombstones keep the row + `deletedAt` + `revision` so digest
 * reconciliation stays faithful. Re-adding a book arrives as Created/Updated with
 * `deletedAt = null`; the upsert clears the tombstone. `isOwnEcho` needs no
 * shield: `@Upsert` is idempotent.
 */
internal fun readingOrderBooksDomain(database: ListenUpDatabase): MirroredDomain<ReadingOrderBookSyncPayload> {
    val apply = ReadingOrderBookMirrorApply(database)
    return MirroredDomain(
        key = SyncDomains.READING_ORDER_BOOKS,
        apply = apply,
        conflict = ConflictPolicy.ServerWins(RevisionGuard { id -> database.readingOrderBookDao().revisionOf(id) }),
        deletes = DeleteSemantics.SoftDelete(apply::tombstoneById),
        digest = fullDigest(database.readingOrderBookDao()::digestRows),
        writes = WriteTier.Outbox(OutboxChannels.ReadingOrderBooks),
    )
}

/** Room mapping for [ReadingOrderBookSyncPayload] junction payloads. */
internal class ReadingOrderBookMirrorApply(
    private val database: ListenUpDatabase,
) : MirrorApply<ReadingOrderBookSyncPayload> {
    override suspend fun upsert(payload: ReadingOrderBookSyncPayload) {
        database.readingOrderBookDao().upsert(
            ReadingOrderBookEntity(
                id = payload.id,
                readingOrderId = payload.readingOrderId,
                bookId = payload.bookId,
                sortOrder = payload.sortOrder,
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
        database.readingOrderBookDao().softDelete(id = id, deletedAt = deletedAt, revision = revision)
    }

    override suspend fun tombstoneFromItem(item: ReadingOrderBookSyncPayload) {
        tombstoneById(
            id = item.id,
            deletedAt = item.deletedAt ?: item.createdAt,
            revision = item.revision,
        )
    }
}
