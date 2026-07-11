package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ReadingOrderBookSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.Reading_order_books
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlin.time.Clock

/**
 * Composite key for `reading_order_books` junction rows.
 *
 * The wire representation is [ReadingOrderBookSyncPayload], which carries
 * [readingOrderId] and [bookId] as top-level fields. [asString] produces the
 * synthetic `"$readingOrderId:$bookId"` key stored in the
 * `reading_order_books.id` column and used by the [SqlSyncableRepository]
 * substrate for revision-cursor identity.
 */
data class ReadingOrderBookId(
    val readingOrderId: String,
    val bookId: String,
) {
    fun asString(): String = "$readingOrderId:$bookId"

    companion object {
        /** Parses a synthetic id string back into its composite parts. */
        fun fromString(s: String): ReadingOrderBookId {
            val colon = s.indexOf(':')
            check(colon > 0) { "Invalid ReadingOrderBookId string: $s" }
            return ReadingOrderBookId(s.substring(0, colon), s.substring(colon + 1))
        }
    }
}

/**
 * SQLDelight syncable repository for the `reading_order_books` userScoped
 * junction — the composite-key, user-scoped sibling of [ReadingOrderRepository],
 * mirroring [ShelfBookRepository].
 *
 * `userScoped = true` — junction rows carry the owning `user_id` (the reading
 * order owner) and sync only to that user. Pull/digest route through the
 * substrate's `*ForUser` variants. The `reading_order_books.id` column stores the
 * synthetic `"$readingOrderId:$bookId"` key the base uses for revision-cursor
 * queries; the natural composite PK `(reading_order_id, book_id)` is the write and
 * lookup key.
 *
 * `sort_order` is the denormalized display position within the reading order,
 * preserved verbatim across the conversion (Int on the wire, INTEGER in SQLite,
 * mapped at the boundary).
 *
 * Service-layer helpers beyond the base substrate — all route through the
 * substrate's [upsert]/[softDelete] so every mutation bumps a revision and
 * publishes to the change bus:
 *  - [listByReadingOrder] — live junction rows for a reading order, ordered by sort order
 *  - [addBook] — append a book to a reading order (next free sort order), idempotent
 *  - [removeBook] — soft-delete one junction row
 *  - [reorder] — rewrite sort order to match a given book ordering
 *  - [softDeleteByReadingOrder] — cascade soft-delete every junction row of a reading order
 */
class ReadingOrderBookRepository(
    db: ListenUpDatabase,
    bus: ChangeBus,
    registry: SyncRegistry,
    clock: Clock = Clock.System,
) : SqlSyncableRepository<ReadingOrderBookSyncPayload, ReadingOrderBookId>(
        db = db,
        bus = bus,
        registry = registry,
        key = SyncDomains.READING_ORDER_BOOKS,
        clock = clock,
    ) {
    override val userScoped: Boolean = true

    override val ReadingOrderBookSyncPayload.id: ReadingOrderBookId get() = ReadingOrderBookId(readingOrderId, bookId)

    override fun ReadingOrderBookSyncPayload.revisionOf(): Long = revision

    /** Converts a [ReadingOrderBookId] to the stored `"$readingOrderId:$bookId"` string. */
    override fun idAsString(id: ReadingOrderBookId): String = id.asString()

    /**
     * [SyncableSubstrateQueries] adapter over the generated
     * [ListenUpDatabase.readingOrderBooksQueries]. Keyed on the synthetic `id`
     * column — the base only ever passes the `"$readingOrderId:$bookId"` string it
     * gets back from [idAsString], so the substrate never needs to decompose it.
     * The two `*ForUser` methods carry the `user_id = :userId` predicate the base
     * applies for a user-scoped aggregate.
     */
    override val substrate: SyncableSubstrateQueries =
        object : SyncableSubstrateQueries {
            override fun existsById(id: String): Boolean = db.readingOrderBooksQueries.existsById(id).executeAsOne()

            override fun softDeleteById(
                id: String,
                revision: Long,
                updatedAt: Long,
                deletedAt: Long,
                clientOpId: String?,
            ): Long =
                db.readingOrderBooksQueries
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
                db.readingOrderBooksQueries
                    .selectIdsAboveRevision(cursor, limit) { id, revision -> IdRev(id, revision) }
                    .executeAsList()

            override fun selectIdRevAtMost(cursor: Long): List<IdRev> =
                db.readingOrderBooksQueries
                    .selectIdRevAtMost(cursor) { id, revision -> IdRev(id, revision) }
                    .executeAsList()

            override fun selectIdsAboveRevisionForUser(
                userId: String,
                cursor: Long,
                limit: Long,
            ): List<IdRev> =
                db.readingOrderBooksQueries
                    .selectIdsAboveRevisionForUser(userId, cursor, limit) { id, revision -> IdRev(id, revision) }
                    .executeAsList()

            override fun selectIdRevAtMostForUser(
                userId: String,
                cursor: Long,
            ): List<IdRev> =
                db.readingOrderBooksQueries
                    .selectIdRevAtMostForUser(userId, cursor) { id, revision -> IdRev(id, revision) }
                    .executeAsList()
        }

    // Tombstone-inclusive read by synthetic id — pullSince/readPayloads must hydrate
    // soft-deleted rows so clients receive tombstones.
    override fun readPayload(idStr: String): ReadingOrderBookSyncPayload? =
        db.readingOrderBooksQueries
            .selectById(idStr)
            .executeAsOneOrNull()
            ?.toSyncPayload()

    override fun readPayloads(idStrs: List<String>): List<ReadingOrderBookSyncPayload> {
        if (idStrs.isEmpty()) return emptyList()
        // SQLite's variable limit (SQLITE_MAX_VARIABLE_NUMBER, 999 by default) caps an
        // `IN (?, ?, …)` list, so batch in chunks of 900 and preserve the requested order.
        val byId =
            idStrs
                .chunked(SQLITE_IN_CHUNK)
                .flatMap { chunk -> db.readingOrderBooksQueries.selectByIds(chunk).executeAsList() }
                .associateBy { it.id }
        return idStrs.mapNotNull { byId[it]?.toSyncPayload() }
    }

    override fun writePayload(
        value: ReadingOrderBookSyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        if (existed) {
            db.readingOrderBooksQueries.update(
                sort_order = value.sortOrder.toLong(),
                revision = rev,
                updated_at = now,
                deleted_at = null,
                client_op_id = clientOpId,
                reading_order_id = value.readingOrderId,
                book_id = value.bookId,
            )
        } else {
            db.readingOrderBooksQueries.insert(
                id = ReadingOrderBookId(value.readingOrderId, value.bookId).asString(),
                user_id = requireNotNull(userId) { "ReadingOrderBookRepository.writePayload requires a userId" },
                reading_order_id = value.readingOrderId,
                book_id = value.bookId,
                sort_order = value.sortOrder.toLong(),
                created_at = now,
                updated_at = now,
                revision = rev,
                deleted_at = null,
                client_op_id = clientOpId,
            )
        }
    }

    /** Returns all live (non-tombstoned) junction rows for [readingOrderId], ordered by sort order. */
    suspend fun listByReadingOrder(readingOrderId: String): List<ReadingOrderBookSyncPayload> =
        suspendTransaction(db) {
            db.readingOrderBooksQueries
                .selectByReadingOrder(readingOrderId)
                .executeAsList()
                .map { it.toSyncPayload() }
        }

    /**
     * Appends [bookId] to [readingOrderId] at the next free sort order, owned by
     * [userId].
     *
     * Idempotent: re-adding a book already live on the reading order is a no-op
     * that returns the existing row unchanged (no duplicate, no spurious revision
     * bump). A previously-removed book is resurrected at the end of the reading
     * order. Routes through the substrate's [upsert], so the write bumps a
     * revision and publishes.
     */
    suspend fun addBook(
        readingOrderId: String,
        bookId: String,
        userId: String,
    ): AppResult<ReadingOrderBookSyncPayload> {
        val existingLive =
            suspendTransaction(db) {
                db.readingOrderBooksQueries
                    .selectLiveByReadingOrderAndBook(readingOrderId, bookId)
                    .executeAsOneOrNull()
                    ?.toSyncPayload()
            }
        if (existingLive != null) return AppResult.Success(existingLive)

        val nextSortOrder = nextSortOrderFor(readingOrderId)
        return upsert(
            ReadingOrderBookSyncPayload(
                id = ReadingOrderBookId(readingOrderId, bookId).asString(),
                readingOrderId = readingOrderId,
                bookId = bookId,
                sortOrder = nextSortOrder,
                revision = 0L,
                updatedAt = 0L,
                createdAt = 0L,
                deletedAt = null,
            ),
            userId = userId,
        )
    }

    /** Soft-deletes the junction row for `(readingOrderId, bookId)`, owned by [userId]. */
    suspend fun removeBook(
        readingOrderId: String,
        bookId: String,
        userId: String,
    ): AppResult<Unit> = softDelete(ReadingOrderBookId(readingOrderId, bookId), userId = userId)

    /**
     * Rewrites every junction row's sort order in [readingOrderId] to its index in
     * [orderedBookIds], owned by [userId]. Each touched row routes through the
     * substrate's [upsert], bumping a revision and publishing — so the reorder
     * propagates to clients row-by-row. Books on the reading order but absent from
     * [orderedBookIds] are left untouched.
     */
    suspend fun reorder(
        readingOrderId: String,
        orderedBookIds: List<String>,
        userId: String,
    ): AppResult<Unit> {
        val live = listByReadingOrder(readingOrderId).associateBy { it.bookId }
        orderedBookIds.forEachIndexed { index, bookId ->
            val current = live[bookId] ?: return@forEachIndexed
            if (current.sortOrder == index) return@forEachIndexed
            val result = upsert(current.copy(sortOrder = index), userId = userId)
            if (result is AppResult.Failure) return result
        }
        return AppResult.Success(Unit)
    }

    /**
     * Cascade soft-deletes every live junction row for [readingOrderId], owned by
     * [userId]. Used as a step in reading-order deletion — each row gets its own
     * revision bump and tombstone publication via the substrate's [softDelete].
     * Returns the number of rows tombstoned.
     */
    suspend fun softDeleteByReadingOrder(
        readingOrderId: String,
        userId: String,
    ): Int {
        val live = listByReadingOrder(readingOrderId)
        for (row in live) {
            softDelete(ReadingOrderBookId(readingOrderId, row.bookId), userId = userId)
        }
        return live.size
    }

    /** Returns the next free sort order for [readingOrderId] — `max(sortOrder) + 1` over live rows, or 0. */
    private suspend fun nextSortOrderFor(readingOrderId: String): Int =
        suspendTransaction(db) {
            db.readingOrderBooksQueries
                .nextSortOrderForReadingOrder(readingOrderId)
                .executeAsOne()
                .toInt()
        }

    /** Maps a generated [Reading_order_books] row to the wire [ReadingOrderBookSyncPayload] DTO (drops `user_id`). */
    private fun Reading_order_books.toSyncPayload(): ReadingOrderBookSyncPayload =
        ReadingOrderBookSyncPayload(
            id = id,
            readingOrderId = reading_order_id,
            bookId = book_id,
            // `sort_order` is INTEGER in SQLite → Long in SQLDelight; the wire field is Int.
            sortOrder = sort_order.toInt(),
            revision = revision,
            updatedAt = updated_at,
            createdAt = created_at,
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
