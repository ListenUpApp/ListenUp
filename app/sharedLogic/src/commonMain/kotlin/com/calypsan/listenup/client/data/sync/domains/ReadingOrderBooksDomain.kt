package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.ReadingOrderBookSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.ReadingOrderBookEntity
import com.calypsan.listenup.client.data.sync.TargetedFetch

/**
 * The `reading_order_books` junction domain (#962): the `book_tags` shape — natural `(readingOrderId,
 * bookId)` primary key, mirrored under the server's opaque wire id ([ReadingOrderBookEntity.syncId],
 * matched by identity, never parsed), so the echo of an optimistic add replaces the row in place
 * whatever id it carries. Server-wins apply, soft tombstones (applied by wire id, because the server
 * blanks the pair on tombstones), full digest, outbox-backed writes.
 *
 * **Access gate.** A membership names a book, so it is visible only while its book is: the server
 * filters the pull and firehose per row, and this gate prunes rows locally when a book leaves the
 * viewer's scope — ordered after the books gate, which decides the book first.
 *
 * **Outbox keying.** Add and remove are keyed by the `(bookId, readingOrderId)` junction, book first, so
 * a drained op re-reads its book's memberships. A reorder is keyed by the bare order id and is
 * deliberately NOT shielded — the `shelf_books` reasoning: shielding every frame for the order would
 * also hold back another device's add of a different book. A stale echo may briefly flicker a queued
 * reorder; the reorder's own echo restores it when it drains.
 */
internal fun readingOrderBooksDomain(database: ListenUpDatabase): MirroredDomain<ReadingOrderBookSyncPayload> {
    val apply = ReadingOrderBookMirrorApply(database)
    return MirroredDomain(
        key = SyncDomains.READING_ORDER_BOOKS,
        apply = apply,
        conflict =
            ConflictPolicy.ServerWins(
                RevisionGuard { syncId -> database.readingOrderBookDao().revisionOfSyncId(syncId) },
            ),
        deletes =
            DeleteSemantics.SoftDelete {
                id,
                deletedAt,
                revision,
                ->
                apply.tombstoneById(id, deletedAt, revision)
            },
        digest = fullDigest(database.readingOrderBookDao()::digestRows),
        writes = WriteTier.Outbox(OutboxChannels.ReadingOrderBooks),
        accessGate =
            AccessGate(
                liveIds = database.readingOrderBookDao()::liveIds,
                tombstoneByIds = database.readingOrderBookDao()::tombstoneByIds,
                delta =
                    AccessDeltaPolicy.Targeted(
                        // After the books gate: a membership is decidable once its book is.
                        order = 7,
                        axis = ScopeAxis.Books,
                        fetchFor = { TargetedFetch.ByBookIds(it) },
                        candidatesFor = { bookIds ->
                            bookIds
                                .chunked(SQLITE_IN_CHUNK)
                                .flatMapTo(mutableSetOf()) { database.readingOrderBookDao().liveSyncIdsForBooks(it) }
                        },
                    ),
            ),
        outboxKeying =
            OutboxKeying(
                keysOf = { setOf(junctionOutboxKey(it.bookId, it.readingOrderId)) },
                refetchFor = { key -> TargetedFetch.ByBookIds(listOf(junctionOutboxParent(key))) },
            ),
    )
}

/** Room mapping for [ReadingOrderBookSyncPayload] junction payloads. */
internal class ReadingOrderBookMirrorApply(
    private val database: ListenUpDatabase,
) : MirrorApply<ReadingOrderBookSyncPayload> {
    override suspend fun upsert(payload: ReadingOrderBookSyncPayload) {
        database.readingOrderBookDao().upsert(
            ReadingOrderBookEntity(
                readingOrderId = payload.readingOrderId,
                bookId = payload.bookId,
                syncId = payload.id,
                position = payload.position,
                revision = payload.revision,
                createdAt = payload.createdAt,
                deletedAt = payload.deletedAt,
            ),
        )
    }

    /** Tombstone from a `Deleted` frame by wire id; the event's own revision makes a replay a no-op. */
    suspend fun tombstoneById(
        id: String,
        deletedAt: Long,
        revision: Long,
    ) {
        database.readingOrderBookDao().tombstoneBySyncId(syncId = id, deletedAt = deletedAt, revision = revision)
    }

    /** Catch-up tombstones ship with the pair blanked, so they apply by wire id only. */
    override suspend fun tombstoneFromItem(item: ReadingOrderBookSyncPayload) {
        tombstoneById(id = item.id, deletedAt = item.deletedAt ?: item.updatedAt, revision = item.revision)
    }
}
