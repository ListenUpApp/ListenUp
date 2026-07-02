package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookTagSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.server.db.sqldelight.Book_tags
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlin.time.Clock

/**
 * Composite key for `book_tags` junction rows.
 *
 * This is an internal server-side type; the wire representation is
 * [BookTagSyncPayload], which carries [bookId] and [tagId] as top-level fields.
 * [asString] produces the synthetic `"$bookId:$tagId"` key stored in the
 * `book_tags.id` column and used by the [SqlSyncableRepository] substrate.
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
 * SQLDelight syncable repository for the `book_tags` global junction — the
 * composite-key sibling of [TagRepository] in the cutover template.
 *
 * Extends [SqlSyncableRepository] with composite-key awareness. The `book_tags.id`
 * column stores the synthetic `"$bookId:$tagId"` key the base uses for revision-cursor
 * queries; the natural composite PK `(book_id, tag_id)` is the write and lookup key.
 *
 * In addition to the standard [upsert] / [softDelete], this repository provides
 * bulk cascade variants used by service-layer delete operations:
 *  - [softDeleteAllForBook] — cascades when a book is deleted
 *  - [softDeleteAllForTag] — cascades when a tag is deleted
 *  - [findBookIdsForTag] — returns book IDs for the post-delete reindex sweep
 */
class BookTagRepository(
    db: ListenUpDatabase,
    bus: ChangeBus,
    registry: SyncRegistry,
    clock: Clock = Clock.System,
) : SqlSyncableRepository<BookTagSyncPayload, BookTagId>(
        db = db,
        bus = bus,
        registry = registry,
        key = SyncDomains.BOOK_TAGS,
        clock = clock,
    ) {
    override val BookTagSyncPayload.id: BookTagId get() = BookTagId(bookId, tagId)

    override fun BookTagSyncPayload.revisionOf(): Long = revision

    /** Converts a [BookTagId] to the stored `"$bookId:$tagId"` string. */
    override fun idAsString(id: BookTagId): String = id.asString()

    /**
     * [SyncableSubstrateQueries] adapter over the generated [ListenUpDatabase.bookTagsQueries].
     *
     * Keyed on the synthetic `id` column — the base only ever passes the
     * `"$bookId:$tagId"` string it gets back from [idAsString], so the substrate never
     * needs to decompose it.
     */
    override val substrate: SyncableSubstrateQueries =
        object : SyncableSubstrateQueries {
            override fun existsById(id: String): Boolean = db.bookTagsQueries.existsById(id).executeAsOne()

            override fun softDeleteById(
                id: String,
                revision: Long,
                updatedAt: Long,
                deletedAt: Long,
                clientOpId: String?,
            ): Long =
                db.bookTagsQueries
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
                db.bookTagsQueries
                    .selectIdsAboveRevision(cursor, limit) { id, revision -> IdRev(id, revision) }
                    .executeAsList()

            override fun selectIdRevAtMost(cursor: Long): List<IdRev> =
                db.bookTagsQueries
                    .selectIdRevAtMost(cursor) { id, revision -> IdRev(id, revision) }
                    .executeAsList()
        }

    // Tombstone-inclusive read by synthetic id — pullSince/readPayloads must hydrate
    // soft-deleted rows so clients receive tombstones.
    override fun readPayload(idStr: String): BookTagSyncPayload? =
        db.bookTagsQueries
            .selectById(idStr)
            .executeAsOneOrNull()
            ?.toPayload()

    override fun readPayloads(idStrs: List<String>): List<BookTagSyncPayload> {
        if (idStrs.isEmpty()) return emptyList()
        // SQLite's variable limit (SQLITE_MAX_VARIABLE_NUMBER, 999 by default) caps an
        // `IN (?, ?, …)` list, so batch in chunks of 900 and preserve the requested order.
        val byId =
            idStrs
                .chunked(SQLITE_IN_CHUNK)
                .flatMap { chunk -> db.bookTagsQueries.selectByIds(chunk).executeAsList() }
                .associateBy { it.id }
        return idStrs.mapNotNull { byId[it]?.toPayload() }
    }

    override fun writePayload(
        value: BookTagSyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        if (existed) {
            db.bookTagsQueries.update(
                revision = rev,
                updated_at = now,
                deleted_at = null,
                client_op_id = clientOpId,
                book_id = value.bookId,
                tag_id = value.tagId,
            )
        } else {
            db.bookTagsQueries.insert(
                id = BookTagId(value.bookId, value.tagId).asString(),
                book_id = value.bookId,
                tag_id = value.tagId,
                created_at = now,
                updated_at = now,
                revision = rev,
                deleted_at = null,
                client_op_id = clientOpId,
            )
        }
    }

    /**
     * Returns all non-tombstoned junction rows for [bookId], ordered by `created_at`.
     */
    suspend fun findAllForBook(bookId: String): List<BookTagSyncPayload> =
        suspendTransaction(db) {
            db.bookTagsQueries
                .selectByBookId(bookId)
                .executeAsList()
                .sortedBy { it.created_at }
                .map { it.toPayload() }
        }

    /**
     * Returns all non-tombstoned junction rows for [tagId].
     */
    suspend fun findAllForTag(tagId: String): List<BookTagSyncPayload> =
        suspendTransaction(db) {
            db.bookTagsQueries
                .selectByTagId(tagId)
                .executeAsList()
                .map { it.toPayload() }
        }

    /**
     * Returns book IDs for all non-tombstoned junction rows linked to [tagId].
     * Used by [com.calypsan.listenup.server.api.TagServiceImpl] to collect the
     * set of books that need FTS reindex after a tag is deleted.
     */
    suspend fun findBookIdsForTag(tagId: String): List<String> =
        suspendTransaction(db) {
            db.bookTagsQueries.selectBookIdsForTag(tagId).executeAsList()
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
            val live = db.bookTagsQueries.selectLiveIdsForBook(bookId).executeAsList()
            tombstoneEach(live)
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
            val live = db.bookTagsQueries.selectLiveIdsForTag(tagId).executeAsList()
            tombstoneEach(live)
            live.size
        }

    /**
     * Tombstones each synthetic id in [syntheticIds] with its own revision bump, inside
     * the caller's open transaction, and registers a per-row after-commit
     * [SyncEvent.Deleted]. The shared body of [softDeleteAllForBook] / [softDeleteAllForTag].
     */
    private fun app.cash.sqldelight.TransactionWithReturn<*>.tombstoneEach(syntheticIds: List<String>) {
        for (syntheticId in syntheticIds) {
            val rev = nextRevision()
            val now = clock.now().toEpochMilliseconds()
            db.bookTagsQueries.softDeleteById(
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
            )
        }
    }

    /** Maps a generated [Book_tags] row to the wire [BookTagSyncPayload] DTO. */
    private fun Book_tags.toPayload(): BookTagSyncPayload =
        BookTagSyncPayload(
            bookId = book_id,
            tagId = tag_id,
            createdAt = created_at,
            revision = revision,
            deletedAt = deleted_at,
        )

    private companion object {
        /**
         * Chunk size for `IN (…)` batch reads. Kept under SQLite's default
         * `SQLITE_MAX_VARIABLE_NUMBER` (999) with headroom for any fixed bind params.
         */
        const val SQLITE_IN_CHUNK = 900
    }
}
