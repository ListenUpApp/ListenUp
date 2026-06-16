package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookMoodSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.server.db.BookMoodsTable
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
 * Composite key for `book_moods` junction rows.
 *
 * This is an internal server-side type; the wire representation is
 * [BookMoodSyncPayload], which carries [bookId] and [moodId] as top-level fields.
 * [asString] produces the synthetic `"$bookId:$moodId"` key stored in
 * [BookMoodsTable.id] and used by the [SyncableRepository] substrate.
 */
data class BookMoodId(
    val bookId: String,
    val moodId: String,
) {
    fun asString(): String = "$bookId:$moodId"

    companion object {
        /** Parses a synthetic id string back into its composite parts. */
        fun fromString(s: String): BookMoodId {
            val colon = s.indexOf(':')
            check(colon > 0) { "Invalid BookMoodId string: $s" }
            return BookMoodId(s.substring(0, colon), s.substring(colon + 1))
        }
    }
}

/**
 * Syncable repository for the `book_moods` global junction.
 *
 * Extends [SyncableRepository] with composite-key awareness. The [BookMoodsTable.id]
 * column stores the synthetic `"$bookId:$moodId"` key that the base class uses for
 * revision-cursor queries; the natural composite PK `(book_id, mood_id)` is the write
 * and lookup key.
 *
 * In addition to the standard [upsert] / [softDelete], this repository provides
 * bulk cascade variants used by service-layer delete operations:
 *  - [softDeleteAllForBook] — cascades when a book is deleted
 *  - [softDeleteAllForMood] — cascades when a mood is deleted
 *  - [findBookIdsForMood] — returns book IDs for any post-delete sweep
 */
class BookMoodRepository(
    db: Database,
    bus: ChangeBus,
    registry: SyncRegistry,
    clock: Clock = Clock.System,
) : SyncableRepository<BookMoodSyncPayload, BookMoodId>(
        db = db,
        table = BookMoodsTable,
        bus = bus,
        registry = registry,
        domainName = "book_moods",
        clock = clock,
    ) {
    override val elementSerializer: KSerializer<BookMoodSyncPayload> = BookMoodSyncPayload.serializer()

    override val BookMoodSyncPayload.id: BookMoodId get() = BookMoodId(bookId, moodId)

    override fun BookMoodSyncPayload.revisionOf(): Long = revision

    /** Returns the synthetic [BookMoodsTable.id] column for the base-class WHERE clauses. */
    override fun idColumn(): Column<String> = BookMoodsTable.id

    /** Converts a [BookMoodId] to the stored `"$bookId:$moodId"` string. */
    override fun idAsString(id: BookMoodId): String = id.asString()

    override suspend fun readPayload(idStr: String): BookMoodSyncPayload? {
        val key = BookMoodId.fromString(idStr)
        return BookMoodsTable
            .selectAll()
            .where { (BookMoodsTable.bookId eq key.bookId) and (BookMoodsTable.moodId eq key.moodId) }
            .firstOrNull()
            ?.let { row ->
                BookMoodSyncPayload(
                    bookId = row[BookMoodsTable.bookId],
                    moodId = row[BookMoodsTable.moodId],
                    createdAt = row[BookMoodsTable.createdAt],
                    revision = row[BookMoodsTable.revision],
                    deletedAt = row[BookMoodsTable.deletedAt],
                )
            }
    }

    override suspend fun writePayload(
        value: BookMoodSyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        val syntheticId = BookMoodId(value.bookId, value.moodId).asString()
        if (existed) {
            BookMoodsTable.update({
                (BookMoodsTable.bookId eq value.bookId) and (BookMoodsTable.moodId eq value.moodId)
            }) { stmt ->
                stmt[BookMoodsTable.revision] = rev
                stmt[BookMoodsTable.updatedAt] = now
                stmt[BookMoodsTable.deletedAt] = null
                stmt[BookMoodsTable.clientOpId] = clientOpId
            }
        } else {
            BookMoodsTable.insert { stmt ->
                stmt[BookMoodsTable.id] = syntheticId
                stmt[BookMoodsTable.bookId] = value.bookId
                stmt[BookMoodsTable.moodId] = value.moodId
                stmt[BookMoodsTable.createdAt] = now
                stmt[BookMoodsTable.updatedAt] = now
                stmt[BookMoodsTable.revision] = rev
                stmt[BookMoodsTable.deletedAt] = null
                stmt[BookMoodsTable.clientOpId] = clientOpId
            }
        }
    }

    /**
     * Returns all non-tombstoned junction rows for [bookId], ordered by [BookMoodsTable.createdAt].
     */
    suspend fun findAllForBook(bookId: String): List<BookMoodSyncPayload> =
        suspendTransaction(db) {
            BookMoodsTable
                .selectAll()
                .where { (BookMoodsTable.bookId eq bookId) and BookMoodsTable.deletedAt.isNull() }
                .orderBy(BookMoodsTable.createdAt)
                .map { row ->
                    BookMoodSyncPayload(
                        bookId = row[BookMoodsTable.bookId],
                        moodId = row[BookMoodsTable.moodId],
                        createdAt = row[BookMoodsTable.createdAt],
                        revision = row[BookMoodsTable.revision],
                        deletedAt = row[BookMoodsTable.deletedAt],
                    )
                }
        }

    /**
     * Returns all non-tombstoned junction rows for [moodId].
     */
    suspend fun findAllForMood(moodId: String): List<BookMoodSyncPayload> =
        suspendTransaction(db) {
            BookMoodsTable
                .selectAll()
                .where { (BookMoodsTable.moodId eq moodId) and BookMoodsTable.deletedAt.isNull() }
                .map { row ->
                    BookMoodSyncPayload(
                        bookId = row[BookMoodsTable.bookId],
                        moodId = row[BookMoodsTable.moodId],
                        createdAt = row[BookMoodsTable.createdAt],
                        revision = row[BookMoodsTable.revision],
                        deletedAt = row[BookMoodsTable.deletedAt],
                    )
                }
        }

    /**
     * Returns book IDs for all non-tombstoned junction rows linked to [moodId].
     * Used by [com.calypsan.listenup.server.api.MoodServiceImpl] to collect the
     * set of books affected by a mood deletion.
     */
    suspend fun findBookIdsForMood(moodId: String): List<String> =
        suspendTransaction(db) {
            BookMoodsTable
                .selectAll()
                .where { (BookMoodsTable.moodId eq moodId) and BookMoodsTable.deletedAt.isNull() }
                .map { row -> row[BookMoodsTable.bookId] }
        }

    /**
     * Soft-deletes the junction row for `(bookId, moodId)`. Bumps revision and publishes
     * [SyncEvent.Deleted] to the change bus. Returns [AppResult.Failure] if no live
     * row exists for this pair.
     */
    suspend fun softDelete(
        bookId: String,
        moodId: String,
        clientOpId: String? = null,
    ): AppResult<Unit> = softDelete(BookMoodId(bookId, moodId), clientOpId = clientOpId)

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
                BookMoodsTable
                    .selectAll()
                    .where { (BookMoodsTable.bookId eq bookId) and BookMoodsTable.deletedAt.isNull() }
                    .map { row -> row[BookMoodsTable.id] }

            for (syntheticId in live) {
                val rev = nextRevision()
                val now = clock.now().toEpochMilliseconds()
                BookMoodsTable.update({ BookMoodsTable.id eq syntheticId }) { stmt ->
                    stmt[BookMoodsTable.revision] = rev
                    stmt[BookMoodsTable.updatedAt] = now
                    stmt[BookMoodsTable.deletedAt] = now
                    stmt[BookMoodsTable.clientOpId] = null
                }
                bus.publish(
                    repo = this@BookMoodRepository,
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
     * Bulk soft-deletes all junction rows for [moodId]. Used as a cascade step in
     * mood deletion — called inside the same transaction as the mood's own soft-delete.
     *
     * Each row gets its own revision bump and [SyncEvent.Deleted] publication so
     * clients receive per-row tombstones. Returns the number of rows tombstoned.
     */
    suspend fun softDeleteAllForMood(moodId: String): Int =
        suspendTransaction(db) {
            val live =
                BookMoodsTable
                    .selectAll()
                    .where { (BookMoodsTable.moodId eq moodId) and BookMoodsTable.deletedAt.isNull() }
                    .map { row -> row[BookMoodsTable.id] }

            for (syntheticId in live) {
                val rev = nextRevision()
                val now = clock.now().toEpochMilliseconds()
                BookMoodsTable.update({ BookMoodsTable.id eq syntheticId }) { stmt ->
                    stmt[BookMoodsTable.revision] = rev
                    stmt[BookMoodsTable.updatedAt] = now
                    stmt[BookMoodsTable.deletedAt] = now
                    stmt[BookMoodsTable.clientOpId] = null
                }
                bus.publish(
                    repo = this@BookMoodRepository,
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
