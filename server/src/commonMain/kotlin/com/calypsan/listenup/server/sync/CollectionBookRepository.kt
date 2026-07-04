package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.server.db.sqldelight.Collection_books
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import app.cash.sqldelight.db.SqlDriver
import kotlin.time.Clock

/**
 * Composite key for `collection_books` junction rows.
 *
 * The wire representation is [CollectionBookSyncPayload], which carries [collectionId] and
 * [bookId] as top-level fields. [asString] produces the synthetic `"$collectionId:$bookId"`
 * key stored in the `collection_books.id` column and used by the [SqlSyncableRepository]
 * substrate for revision-cursor identity.
 */
data class CollectionBookId(
    val collectionId: String,
    val bookId: String,
) {
    fun asString(): String = "$collectionId:$bookId"

    companion object {
        /** Parses a synthetic id string back into its composite parts. */
        fun fromString(s: String): CollectionBookId {
            val colon = s.indexOf(':')
            check(colon > 0) { "Invalid CollectionBookId string: $s" }
            return CollectionBookId(s.substring(0, colon), s.substring(colon + 1))
        }
    }
}

/**
 * SQLDelight syncable repository for the `collection_books` global junction — the composite-key
 * sibling of [CollectionRepository].
 *
 * The `collection_books.id` column stores the synthetic `"$collectionId:$bookId"` key the base
 * uses for revision-cursor queries; the natural composite PK `(collection_id, book_id)` is the
 * write and lookup key.
 *
 * **Access-filtered sync.** A member's membership catch-up/digest must exclude membership rows
 * for collections they cannot reach (including the system collections), so the firehose arrives
 * with a non-null [SqlFragment] `extraWhere` (the `accessibleCollectionBookIdsSql` rule). The base
 * [SqlSyncableRepository] splices it engine-neutrally over the injected [SqlDriver]; this class
 * only wires that driver. The unfiltered (admin / null) path takes the base's substrate read
 * unchanged.
 *
 * In addition to the standard [upsert] / [softDelete], this repository provides bulk-cascade
 * variants used by service-layer delete operations:
 *  - [softDeleteAllForCollection] — cascades when a collection is deleted
 *  - [findBookIdsForCollection] — live book IDs for a collection
 *  - [findCollectionIdsForBook] — live collection IDs for a book
 *  - [countLiveForCollection] — count of live junction rows for a collection
 */
class CollectionBookRepository(
    db: ListenUpDatabase,
    bus: ChangeBus,
    registry: SyncRegistry,
    override val driver: SqlDriver,
    clock: Clock = Clock.System,
) : SqlSyncableRepository<CollectionBookSyncPayload, CollectionBookId>(
        db = db,
        bus = bus,
        registry = registry,
        key = SyncDomains.COLLECTION_BOOKS,
        clock = clock,
    ) {
    override val CollectionBookSyncPayload.id: CollectionBookId get() = CollectionBookId(collectionId, bookId)

    override fun CollectionBookSyncPayload.revisionOf(): Long = revision

    /** Converts a [CollectionBookId] to the stored `"$collectionId:$bookId"` string. */
    override fun idAsString(id: CollectionBookId): String = id.asString()

    /**
     * [SyncableSubstrateQueries] adapter over the generated [ListenUpDatabase.collectionBooksQueries].
     * Keyed on the synthetic `id` column. Global aggregate — `*ForUser` stays the throwing defaults.
     */
    override val substrate: SyncableSubstrateQueries =
        object : SyncableSubstrateQueries {
            override fun existsById(id: String): Boolean = db.collectionBooksQueries.existsById(id).executeAsOne()

            override fun softDeleteById(
                id: String,
                revision: Long,
                updatedAt: Long,
                deletedAt: Long,
                clientOpId: String?,
            ): Long =
                db.collectionBooksQueries
                    .softDeleteById(
                        revision = revision,
                        updated_at = updatedAt,
                        deleted_at = deletedAt,
                        client_op_id = clientOpId,
                        id = id,
                    ).value

            override fun selectIdsAboveRevision(
                cursor: Long,
                limit: Long,
            ): List<IdRev> =
                db.collectionBooksQueries
                    .selectIdsAboveRevision(cursor, limit) { id, revision -> IdRev(id, revision) }
                    .executeAsList()

            override fun selectIdRevAtMost(cursor: Long): List<IdRev> =
                db.collectionBooksQueries
                    .selectIdRevAtMost(cursor) { id, revision -> IdRev(id, revision) }
                    .executeAsList()
        }

    // Tombstone-inclusive read by synthetic id — pullSince/readPayloads must hydrate soft-deleted
    // rows so clients receive tombstones.
    override fun readPayload(idStr: String): CollectionBookSyncPayload? =
        db.collectionBooksQueries
            .selectById(idStr)
            .executeAsOneOrNull()
            ?.toSyncPayload()

    override fun readPayloads(idStrs: List<String>): List<CollectionBookSyncPayload> {
        if (idStrs.isEmpty()) return emptyList()
        val byId =
            idStrs
                .chunked(SQLITE_IN_CHUNK)
                .flatMap { chunk -> db.collectionBooksQueries.selectByIds(chunk).executeAsList() }
                .associateBy { it.id }
        return idStrs.mapNotNull { byId[it]?.toSyncPayload() }
    }

    override fun writePayload(
        value: CollectionBookSyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        if (existed) {
            db.collectionBooksQueries.update(
                revision = rev,
                updated_at = now,
                deleted_at = null,
                client_op_id = clientOpId,
                collection_id = value.collectionId,
                book_id = value.bookId,
            )
        } else {
            db.collectionBooksQueries.insert(
                id = CollectionBookId(value.collectionId, value.bookId).asString(),
                collection_id = value.collectionId,
                book_id = value.bookId,
                created_at = now,
                updated_at = now,
                revision = rev,
                deleted_at = null,
                client_op_id = clientOpId,
            )
        }
    }

    /** Returns all live (non-tombstoned) book IDs in [collectionId]. */
    suspend fun findBookIdsForCollection(collectionId: String): List<String> =
        suspendTransaction(db) {
            db.collectionBooksQueries
                .liveBookIdsForCollection(collectionId)
                .executeAsList()
        }

    /**
     * Returns all live (non-tombstoned) collection IDs that contain [bookId]. Backs
     * [com.calypsan.listenup.server.api.CollectionServiceImpl.setBookCollections]'s current-set diff.
     */
    suspend fun findCollectionIdsForBook(bookId: String): List<String> =
        suspendTransaction(db) {
            db.collectionBooksQueries
                .liveCollectionIdsForBook(bookId)
                .executeAsList()
        }

    /** Returns the count of live (non-tombstoned) junction rows for [collectionId]. */
    suspend fun countLiveForCollection(collectionId: String): Long =
        suspendTransaction(db) {
            db.collectionBooksQueries
                .countLiveForCollection(collectionId)
                .executeAsOne()
        }

    /**
     * Soft-deletes the junction row for `(collectionId, bookId)`. Bumps revision and publishes
     * [SyncEvent.Deleted] to the change bus. Returns [AppResult.Failure] if no live row exists
     * for this pair.
     */
    suspend fun softDelete(
        collectionId: String,
        bookId: String,
        clientOpId: String? = null,
    ): AppResult<Unit> = softDelete(CollectionBookId(collectionId, bookId), clientOpId = clientOpId)

    /**
     * Bulk soft-deletes all junction rows for [collectionId]. Used as a cascade step in
     * collection deletion. Each row gets its own revision bump and [SyncEvent.Deleted] publication
     * so clients receive per-row tombstones. Returns the number of rows tombstoned.
     *
     * Mirrors the base's per-row `softDelete`: the revision is bumped and the row stamped inside
     * the open transaction, and the emit is deferred to after-commit (FIFO publish order) via
     * [emitAfterCommit] — the engine-native equivalent of the Exposed base's per-row outbox.
     */
    suspend fun softDeleteAllForCollection(collectionId: String): Int =
        suspendTransaction(db) {
            val live = db.collectionBooksQueries.liveIdsForCollection(collectionId).executeAsList()
            for (syntheticId in live) {
                val rev = nextRevision()
                val now = clock.now().toEpochMilliseconds()
                db.collectionBooksQueries.softDeleteById(
                    revision = rev,
                    updated_at = now,
                    deleted_at = now,
                    client_op_id = null,
                    id = syntheticId,
                )
                emitAfterCommit(
                    event =
                        SyncEvent.Deleted(
                            id = syntheticId,
                            revision = rev,
                            occurredAt = now,
                            clientOpId = null,
                        ),
                    userId = null,
                )
            }
            live.size
        }

    /** Maps a generated [Collection_books] row to the wire [CollectionBookSyncPayload] DTO. */
    private fun Collection_books.toSyncPayload(): CollectionBookSyncPayload =
        CollectionBookSyncPayload(
            collectionId = collection_id,
            bookId = book_id,
            createdAt = created_at,
            revision = revision,
            deletedAt = deleted_at,
        )

    private companion object {
        /** Chunk size for `IN (…)` batch reads. Kept under SQLite's default 999 var limit. */
        const val SQLITE_IN_CHUNK = 900
    }
}
