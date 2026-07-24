package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.ShelfBookSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.ShelfBookEntity

/**
 * The `shelf_books` junction domain (Shelves — Room v26): the synthetic
 * `"$shelfId:$bookId"` envelope id IS the local primary key, so events apply by id
 * alone — no composite parsing. Server-wins apply, soft tombstones, full digest,
 * outbox-backed writes.
 *
 * Shelf membership is user-scoped own-data — no [AccessGate] (the collections
 * junction is access-filtered; shelves are not). Junction rule: tombstones keep the
 * row + `deletedAt` + `revision` so digest reconciliation stays faithful.
 *
 * **Re-add semantics.** Re-adding a book arrives as Created/Updated with
 * `deletedAt = null`; the upsert clears the tombstone.
 *
 * **Outbox writes.** Adding and removing a book write the junction optimistically and queue a
 * durable op on [OutboxChannels.ShelfBooks], keyed by the same `"$shelfId:$bookId"` envelope id;
 * the in-flight shield defers the junction's own echo until that op drains. Unlike
 * book_tags/book_moods, add is offline-first too — the book already exists, so no server id is minted.
 */
internal fun shelfBooksDomain(database: ListenUpDatabase): MirroredDomain<ShelfBookSyncPayload> {
    val apply = ShelfBookMirrorApply(database)
    return MirroredDomain(
        key = SyncDomains.SHELF_BOOKS,
        apply = apply,
        conflict = ConflictPolicy.ServerWins(RevisionGuard { id -> database.shelfBookDao().revisionOf(id) }),
        deletes = DeleteSemantics.SoftDelete(apply::tombstoneById),
        digest = fullDigest(database.shelfBookDao()::digestRows),
        writes = WriteTier.Outbox(OutboxChannels.ShelfBooks),
    )
}

/** Room mapping for [ShelfBookSyncPayload] junction payloads. */
internal class ShelfBookMirrorApply(
    private val database: ListenUpDatabase,
) : MirrorApply<ShelfBookSyncPayload> {
    /**
     * Unlike the other three junction domains — whose Room primary key IS the natural pair, so a
     * plain `@Upsert` self-heals a client-minted id to the server-echoed one in place — `shelf_books`'
     * primary key is its opaque wire [ShelfBookEntity.id] (SERVER-SYNC-04). A client-originated
     * optimistic add mints its own id before any server round-trip (see
     * `ShelfRepositoryImpl.addBooksToShelf`); if the server's later Created echo carries a
     * DIFFERENT id for the SAME `(shelfId, bookId)` pair, a plain `@Upsert` would leave both rows —
     * the stale client-minted one orphaned forever. Reconcile first: an existing row for the pair
     * with a different id is deleted before the incoming payload is upserted, so the pair converges
     * on exactly one row.
     */
    override suspend fun upsert(payload: ShelfBookSyncPayload) {
        val existing = database.shelfBookDao().findByShelfAndBook(payload.shelfId, payload.bookId)
        if (existing != null && existing.id != payload.id) {
            database.shelfBookDao().deleteById(existing.id)
        }
        database.shelfBookDao().upsert(
            ShelfBookEntity(
                id = payload.id,
                shelfId = payload.shelfId,
                bookId = payload.bookId,
                sortOrder = payload.sortOrder,
                revision = payload.revision,
                deletedAt = payload.deletedAt,
                updatedAt = payload.updatedAt,
                createdAt = payload.createdAt,
            ),
        )
    }

    /**
     * Unlike book_tags/book_moods (whose DAO advances its own revision), shelf_books'
     * softDelete takes the event revision — the id is already the primary key, so no
     * composite parse.
     */
    suspend fun tombstoneById(
        id: String,
        deletedAt: Long,
        revision: Long,
    ) {
        database.shelfBookDao().softDelete(id = id, deletedAt = deletedAt, revision = revision)
    }

    override suspend fun tombstoneFromItem(item: ShelfBookSyncPayload) {
        tombstoneById(
            id = item.id,
            deletedAt = item.deletedAt ?: item.createdAt,
            revision = item.revision,
        )
    }
}
