package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.ShelfBookSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.ShelfBookEntity

/**
 * The `shelf_books` junction domain (Shelves — Room v26): the synthetic
 * `"$shelfId:$bookId"` envelope id IS the local primary key, so events apply by id
 * alone — no composite parsing. Server-wins apply, soft tombstones, full digest,
 * online-only writes.
 *
 * Shelf membership is user-scoped own-data — no [AccessGate] (the collections
 * junction is access-filtered; shelves are not). Junction rule: tombstones keep the
 * row + `deletedAt` + `revision` so digest reconciliation stays faithful.
 *
 * **Re-add semantics.** Re-adding a book arrives as Created/Updated with
 * `deletedAt = null`; the upsert clears the tombstone. `isOwnEcho` needs no shield:
 * `@Upsert` is idempotent.
 */
internal fun shelfBooksDomain(database: ListenUpDatabase): MirroredDomain<ShelfBookSyncPayload> =
    MirroredDomain(
        key = SyncDomains.SHELF_BOOKS,
        syncIdOf = { it.id },
        apply = ShelfBookMirrorApply(database),
        conflict = ConflictPolicy.ServerWins(),
        deletes = DeleteSemantics.SoftDelete,
        digest = fullDigest(database.shelfBookDao()::digestRows),
        writes = WriteTier.OnlineOnly,
        revisionGuard =
            RevisionGuard(
                incomingRevision = { it.revision },
                localRevision = { id -> database.shelfBookDao().revisionOf(id) },
            ),
    )

/** Room mapping for [ShelfBookSyncPayload] junction payloads. */
internal class ShelfBookMirrorApply(
    private val database: ListenUpDatabase,
) : MirrorApply<ShelfBookSyncPayload> {
    override suspend fun upsert(payload: ShelfBookSyncPayload) {
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
    override suspend fun tombstoneById(
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
