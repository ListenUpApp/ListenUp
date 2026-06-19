package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookTagSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.server.db.BookTagsTable
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
 * Composite key for `book_tags` junction rows.
 *
 * This is an internal server-side type; the wire representation is
 * [BookTagSyncPayload], which carries [bookId] and [tagId] as top-level fields.
 * [asString] produces the synthetic `"$bookId:$tagId"` key stored in
 * [BookTagsTable.id] and used by the [SyncableRepository] substrate.
 */
data class BookTagId(
    val bookId: String,
    val tagId: String,
) {
    fun asString(): String = "$bookId:$tagId"

    companion object {
        /** Parses a synthetic id string back into its composite parts. */
        fun fromString(s: String): BookTagId {
            val colon = s.indexOf(':')
            check(colon > 0) { "Invalid BookTagId string: $s" }
            return BookTagId(s.substring(0, colon), s.substring(colon + 1))
        }
    }
}

/**
 * Syncable repository for the `book_tags` global junction.
 *
 * Extends [SyncableRepository] with composite-key awareness. The [BookTagsTable.id]
 * column stores the synthetic `"$bookId:$tagId"` key that the base class uses for
 * revision-cursor queries; the natural composite PK `(book_id, tag_id)` is the write
 * and lookup key.
 *
 * In addition to the standard [upsert] / [softDelete], this repository provides
 * bulk cascade variants used by service-layer delete operations:
 *  - [softDeleteAllForBook] — cascades when a book is deleted
 *  - [softDeleteAllForTag] — cascades when a tag is deleted
 *  - [findBookIdsForTag] — returns book IDs for the post-delete reindex sweep
 */
class BookTagRepository(
    db: Database,
    bus: ChangeBus,
    registry: SyncRegistry,
    clock: Clock = Clock.System,
) : SyncableRepository<BookTagSyncPayload, BookTagId>(
        db = db,
        table = BookTagsTable,
        bus = bus,
        registry = registry,
        domainName = "book_tags",
        clock = clock,
    ) {
    override val elementSerializer: KSerializer<BookTagSyncPayload> = BookTagSyncPayload.serializer()

    override val BookTagSyncPayload.id: BookTagId get() = BookTagId(bookId, tagId)

    override fun BookTagSyncPayload.revisionOf(): Long = revision

    /** Returns the synthetic [BookTagsTable.id] column for the base-class WHERE clauses. */
    override fun idColumn(): Column<String> = BookTagsTable.id

    /** Converts a [BookTagId] to the stored `"$bookId:$tagId"` string. */
    override fun idAsString(id: BookTagId): String = id.asString()

    override suspend fun readPayload(idStr: String): BookTagSyncPayload? {
        val key = BookTagId.fromString(idStr)
        return BookTagsTable
            .selectAll()
            .where { (BookTagsTable.bookId eq key.bookId) and (BookTagsTable.tagId eq key.tagId) }
            .firstOrNull()
            ?.let { row ->
                BookTagSyncPayload(
                    bookId = row[BookTagsTable.bookId],
                    tagId = row[BookTagsTable.tagId],
                    createdAt = row[BookTagsTable.createdAt],
                    revision = row[BookTagsTable.revision],
                    deletedAt = row[BookTagsTable.deletedAt],
                )
            }
    }

    override suspend fun writePayload(
        value: BookTagSyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        val syntheticId = BookTagId(value.bookId, value.tagId).asString()
        if (existed) {
            BookTagsTable.update({
                (BookTagsTable.bookId eq value.bookId) and (BookTagsTable.tagId eq value.tagId)
            }) { stmt ->
                stmt[BookTagsTable.revision] = rev
                stmt[BookTagsTable.updatedAt] = now
                stmt[BookTagsTable.deletedAt] = null
                stmt[BookTagsTable.clientOpId] = clientOpId
            }
        } else {
            BookTagsTable.insert { stmt ->
                stmt[BookTagsTable.id] = syntheticId
                stmt[BookTagsTable.bookId] = value.bookId
                stmt[BookTagsTable.tagId] = value.tagId
                stmt[BookTagsTable.createdAt] = now
                stmt[BookTagsTable.updatedAt] = now
                stmt[BookTagsTable.revision] = rev
                stmt[BookTagsTable.deletedAt] = null
                stmt[BookTagsTable.clientOpId] = clientOpId
            }
        }
    }

    /**
     * Returns all non-tombstoned junction rows for [bookId], ordered by [BookTagsTable.createdAt].
     */
    suspend fun findAllForBook(bookId: String): List<BookTagSyncPayload> =
        suspendTransaction(db) {
            BookTagsTable
                .selectAll()
                .where { (BookTagsTable.bookId eq bookId) and BookTagsTable.deletedAt.isNull() }
                .orderBy(BookTagsTable.createdAt)
                .map { row ->
                    BookTagSyncPayload(
                        bookId = row[BookTagsTable.bookId],
                        tagId = row[BookTagsTable.tagId],
                        createdAt = row[BookTagsTable.createdAt],
                        revision = row[BookTagsTable.revision],
                        deletedAt = row[BookTagsTable.deletedAt],
                    )
                }
        }

    /**
     * Returns all non-tombstoned junction rows for [tagId].
     */
    suspend fun findAllForTag(tagId: String): List<BookTagSyncPayload> =
        suspendTransaction(db) {
            BookTagsTable
                .selectAll()
                .where { (BookTagsTable.tagId eq tagId) and BookTagsTable.deletedAt.isNull() }
                .map { row ->
                    BookTagSyncPayload(
                        bookId = row[BookTagsTable.bookId],
                        tagId = row[BookTagsTable.tagId],
                        createdAt = row[BookTagsTable.createdAt],
                        revision = row[BookTagsTable.revision],
                        deletedAt = row[BookTagsTable.deletedAt],
                    )
                }
        }

    /**
     * Returns book IDs for all non-tombstoned junction rows linked to [tagId].
     * Used by [com.calypsan.listenup.server.api.TagServiceImpl] to collect the
     * set of books that need FTS reindex after a tag is deleted.
     */
    suspend fun findBookIdsForTag(tagId: String): List<String> =
        suspendTransaction(db) {
            BookTagsTable
                .selectAll()
                .where { (BookTagsTable.tagId eq tagId) and BookTagsTable.deletedAt.isNull() }
                .map { row -> row[BookTagsTable.bookId] }
        }

    /**
     * Soft-deletes the junction row for `(bookId, tagId)`. Bumps revision and publishes
     * [SyncEvent.Deleted] to the change bus. Returns [AppResult.Failure] if no live
     * row exists for this pair.
     */
    suspend fun softDelete(
        bookId: String,
        tagId: String,
        clientOpId: String? = null,
    ): AppResult<Unit> = softDelete(BookTagId(bookId, tagId), clientOpId = clientOpId)

    /**
     * Bulk soft-deletes all junction rows for [bookId]. Used as a cascade step in
     * book deletion — called inside the same transaction as the book's own soft-delete.
     *
     * Each row gets its own revision bump and [SyncEvent.Deleted] publication so
     * clients receive per-row tombstones. Returns the number of rows tombstoned.
     */
    suspend fun softDeleteAllForBook(bookId: String): Int =
        suspendTransaction(db) {
            val live =
                BookTagsTable
                    .selectAll()
                    .where { (BookTagsTable.bookId eq bookId) and BookTagsTable.deletedAt.isNull() }
                    .map { row -> row[BookTagsTable.id] }

            for (syntheticId in live) {
                val rev = nextRevision()
                val now = clock.now().toEpochMilliseconds()
                BookTagsTable.update({ BookTagsTable.id eq syntheticId }) { stmt ->
                    stmt[BookTagsTable.revision] = rev
                    stmt[BookTagsTable.updatedAt] = now
                    stmt[BookTagsTable.deletedAt] = now
                    stmt[BookTagsTable.clientOpId] = null
                }
                bus.publish(
                    repo = this@BookTagRepository,
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

    /**
     * Bulk soft-deletes all junction rows for [tagId]. Used as a cascade step in
     * tag deletion — called inside the same transaction as the tag's own soft-delete.
     *
     * Each row gets its own revision bump and [SyncEvent.Deleted] publication so
     * clients receive per-row tombstones. Returns the number of rows tombstoned.
     */
    suspend fun softDeleteAllForTag(tagId: String): Int =
        suspendTransaction(db) {
            val live =
                BookTagsTable
                    .selectAll()
                    .where { (BookTagsTable.tagId eq tagId) and BookTagsTable.deletedAt.isNull() }
                    .map { row -> row[BookTagsTable.id] }

            for (syntheticId in live) {
                val rev = nextRevision()
                val now = clock.now().toEpochMilliseconds()
                BookTagsTable.update({ BookTagsTable.id eq syntheticId }) { stmt ->
                    stmt[BookTagsTable.revision] = rev
                    stmt[BookTagsTable.updatedAt] = now
                    stmt[BookTagsTable.deletedAt] = now
                    stmt[BookTagsTable.clientOpId] = null
                }
                bus.publish(
                    repo = this@BookTagRepository,
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
