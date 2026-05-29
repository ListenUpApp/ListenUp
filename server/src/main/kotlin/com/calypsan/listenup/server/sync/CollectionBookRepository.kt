package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.server.db.CollectionBooksTable
import kotlin.time.Clock
import kotlinx.serialization.KSerializer
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Composite key for `collection_books` junction rows.
 *
 * This is an internal server-side type; the wire representation is
 * [CollectionBookSyncPayload], which carries [collectionId] and [bookId] as top-level
 * fields. [asString] produces the synthetic `"$collectionId:$bookId"` key stored in
 * [CollectionBooksTable.id] and used by the [SyncableRepository] substrate.
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
 * Syncable repository for the `collection_books` global junction.
 *
 * Extends [SyncableRepository] with composite-key awareness. The [CollectionBooksTable.id]
 * column stores the synthetic `"$collectionId:$bookId"` key that the base class uses for
 * revision-cursor queries; the natural composite PK `(collection_id, book_id)` is the write
 * and lookup key.
 *
 * In addition to the standard [upsert] / [softDelete], this repository provides
 * bulk cascade variants used by service-layer delete operations:
 *  - [softDeleteAllForCollection] — cascades when a collection is deleted
 *  - [findBookIdsForCollection] — returns live book IDs for a collection
 *  - [findCollectionIdsForBook] — returns live collection IDs for a book
 *  - [countLiveForCollection] — count of live junction rows for a collection
 */
class CollectionBookRepository(
    db: Database,
    bus: ChangeBus,
    registry: SyncRegistry,
    clock: Clock = Clock.System,
) : SyncableRepository<CollectionBookSyncPayload, CollectionBookId>(
        db = db,
        table = CollectionBooksTable,
        bus = bus,
        registry = registry,
        domainName = "collection_books",
        clock = clock,
    ) {
    override val elementSerializer: KSerializer<CollectionBookSyncPayload> = CollectionBookSyncPayload.serializer()

    override val CollectionBookSyncPayload.id: CollectionBookId get() = CollectionBookId(collectionId, bookId)

    override fun CollectionBookSyncPayload.revisionOf(): Long = revision

    /** Returns the synthetic [CollectionBooksTable.id] column for the base-class WHERE clauses. */
    override fun idColumn(): Column<String> = CollectionBooksTable.id

    /** Converts a [CollectionBookId] to the stored `"$collectionId:$bookId"` string. */
    override fun idAsString(id: CollectionBookId): String = id.asString()

    override suspend fun readPayload(idStr: String): CollectionBookSyncPayload? {
        val key = CollectionBookId.fromString(idStr)
        return CollectionBooksTable
            .selectAll()
            .where {
                (CollectionBooksTable.collectionId eq key.collectionId) and
                    (CollectionBooksTable.bookId eq key.bookId)
            }.firstOrNull()
            ?.let { row ->
                CollectionBookSyncPayload(
                    collectionId = row[CollectionBooksTable.collectionId],
                    bookId = row[CollectionBooksTable.bookId],
                    createdAt = row[CollectionBooksTable.createdAt],
                    revision = row[CollectionBooksTable.revision],
                    deletedAt = row[CollectionBooksTable.deletedAt],
                )
            }
    }

    override suspend fun writePayload(
        value: CollectionBookSyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        val syntheticId = CollectionBookId(value.collectionId, value.bookId).asString()
        if (existed) {
            CollectionBooksTable.update({
                (CollectionBooksTable.collectionId eq value.collectionId) and
                    (CollectionBooksTable.bookId eq value.bookId)
            }) { stmt ->
                stmt[CollectionBooksTable.revision] = rev
                stmt[CollectionBooksTable.updatedAt] = now
                stmt[CollectionBooksTable.deletedAt] = null
                stmt[CollectionBooksTable.clientOpId] = clientOpId
            }
        } else {
            CollectionBooksTable.insert { stmt ->
                stmt[CollectionBooksTable.id] = syntheticId
                stmt[CollectionBooksTable.collectionId] = value.collectionId
                stmt[CollectionBooksTable.bookId] = value.bookId
                stmt[CollectionBooksTable.createdAt] = now
                stmt[CollectionBooksTable.updatedAt] = now
                stmt[CollectionBooksTable.revision] = rev
                stmt[CollectionBooksTable.deletedAt] = null
                stmt[CollectionBooksTable.clientOpId] = clientOpId
            }
        }
    }

    /**
     * Returns all live (non-tombstoned) book IDs in [collectionId].
     */
    suspend fun findBookIdsForCollection(collectionId: String): List<String> =
        suspendTransaction(db) {
            CollectionBooksTable
                .selectAll()
                .where {
                    (CollectionBooksTable.collectionId eq collectionId) and
                        CollectionBooksTable.deletedAt.isNull()
                }.map { row -> row[CollectionBooksTable.bookId] }
        }

    /**
     * Returns all live (non-tombstoned) collection IDs that contain [bookId].
     *
     * Staged for Collections-1b `BookAccessPolicy`, whose book-visibility decision asks
     * "which collections contain this book?" — intentionally uncalled in 1a.
     */
    suspend fun findCollectionIdsForBook(bookId: String): List<String> =
        suspendTransaction(db) {
            CollectionBooksTable
                .selectAll()
                .where {
                    (CollectionBooksTable.bookId eq bookId) and
                        CollectionBooksTable.deletedAt.isNull()
                }.map { row -> row[CollectionBooksTable.collectionId] }
        }

    /**
     * Returns the count of live (non-tombstoned) book–collection junction rows for [collectionId].
     */
    suspend fun countLiveForCollection(collectionId: String): Long =
        suspendTransaction(db) {
            CollectionBooksTable
                .selectAll()
                .where {
                    (CollectionBooksTable.collectionId eq collectionId) and
                        CollectionBooksTable.deletedAt.isNull()
                }.count()
        }

    /**
     * Soft-deletes the junction row for `(collectionId, bookId)`. Bumps revision and
     * publishes [SyncEvent.Deleted] to the change bus. Returns [AppResult.Failure] if
     * no live row exists for this pair.
     */
    suspend fun softDelete(
        collectionId: String,
        bookId: String,
        clientOpId: String? = null,
    ): AppResult<Unit> = softDelete(CollectionBookId(collectionId, bookId), clientOpId = clientOpId)

    /**
     * Bulk soft-deletes all junction rows for [collectionId]. Used as a cascade step in
     * collection deletion — called inside the same transaction as the collection's own soft-delete.
     *
     * Each row gets its own revision bump and [SyncEvent.Deleted] publication so
     * clients receive per-row tombstones. Returns the number of rows tombstoned.
     */
    suspend fun softDeleteAllForCollection(collectionId: String): Int =
        suspendTransaction(db) {
            val live =
                CollectionBooksTable
                    .selectAll()
                    .where {
                        (CollectionBooksTable.collectionId eq collectionId) and
                            CollectionBooksTable.deletedAt.isNull()
                    }.map { row -> row[CollectionBooksTable.id] }

            for (syntheticId in live) {
                val rev = nextRevision()
                val now = clock.now().toEpochMilliseconds()
                CollectionBooksTable.update({ CollectionBooksTable.id eq syntheticId }) { stmt ->
                    stmt[CollectionBooksTable.revision] = rev
                    stmt[CollectionBooksTable.updatedAt] = now
                    stmt[CollectionBooksTable.deletedAt] = now
                    stmt[CollectionBooksTable.clientOpId] = null
                }
                bus.publish(
                    repo = this@CollectionBookRepository,
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
}
