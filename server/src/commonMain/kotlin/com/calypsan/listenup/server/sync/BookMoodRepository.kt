package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookMoodSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.server.db.sqldelight.Book_moods
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlin.time.Clock

/**
 * Natural-pair identity for `book_moods` junction rows — the server-internal type the
 * [SqlSyncableRepository] substrate uses to resolve the row's REAL opaque wire id.
 *
 * The wire representation is [BookMoodSyncPayload], which carries [bookId] and [moodId] as
 * top-level fields, plus its own opaque `id` (SERVER-SYNC-04: minted at creation, encodes
 * nothing). [candidateWireId] carries that candidate id through to [idAsString] —
 * client-minted for an offline-first create, server-minted otherwise — for the case where no
 * row exists yet for the pair. When a row already exists (live or tombstoned), [idAsString]
 * discards the candidate and returns the EXISTING row's id instead — "the existing row's id
 * wins" on a natural-pair conflict.
 */
data class BookMoodId(
    val bookId: String,
    val moodId: String,
    val candidateWireId: String = "",
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
 * SQLDelight syncable repository for the `book_moods` global junction — the
 * composite-key sibling of [MoodRepository] and the twin of [BookTagRepository].
 *
 * Extends [SqlSyncableRepository] with composite-key awareness. The `book_moods.id` column
 * stores an opaque per-row id (SERVER-SYNC-04) the base uses for revision-cursor queries; the
 * natural composite PK `(book_id, mood_id)` is the write and lookup key. [idAsString] resolves
 * between the two — see [BookMoodId].
 *
 * In addition to the standard [upsert] / [softDelete], this repository provides
 * bulk cascade variants used by service-layer delete operations:
 *  - [softDeleteAllForBook] — cascades when a book is deleted
 *  - [softDeleteAllForMood] — cascades when a mood is deleted
 *  - [findBookIdsForMood] — returns book IDs for any post-delete sweep
 */
class BookMoodRepository(
    db: ListenUpDatabase,
    bus: ChangeBus,
    registry: SyncRegistry,
    clock: Clock = Clock.System,
) : SqlSyncableRepository<BookMoodSyncPayload, BookMoodId>(
        db = db,
        bus = bus,
        registry = registry,
        key = SyncDomains.BOOK_MOODS,
        clock = clock,
    ) {
    override val BookMoodSyncPayload.id: BookMoodId get() = BookMoodId(bookId, moodId, id)

    /**
     * Resolves [id] to the REAL stored opaque wire id: an existing row's id if one already
     * exists for the natural pair (live or tombstoned — "the existing row's id wins" on a
     * natural-pair conflict), else [BookMoodId.candidateWireId] as-is.
     *
     * The candidate is NOT minted here — minting is the call site's job (client-minted for an
     * offline-first create, server-minted otherwise, e.g. `Uuid.random().toString()` in
     * `MoodServiceImpl`/`BookMoodWriter`). Minting inside this function would be unsafe:
     * [upsert] calls [idAsString] once to decide `existed` and again inside [writePayload]'s
     * insert branch to compute the row's actual `id` — two calls that must resolve identically
     * within the same transaction. A fresh mint per call would make them diverge, and the
     * second (real) write would never match the first (assumed) id — see
     * [SqlSyncableRepository.upsert]'s `readPayload` immediately after `writePayload`.
     */
    override fun idAsString(id: BookMoodId): String =
        db.bookMoodsQueries
            .selectIdByNaturalPair(id.bookId, id.moodId)
            .executeAsOneOrNull()
            ?: id.candidateWireId

    /**
     * [SyncableSubstrateQueries] adapter over the generated [ListenUpDatabase.bookMoodsQueries].
     *
     * Keyed on the synthetic `id` column — the base only ever passes the
     * `"$bookId:$moodId"` string it gets back from [idAsString], so the substrate never
     * needs to decompose it.
     */
    override val substrate: SyncableSubstrateQueries =
        object : SyncableSubstrateQueries {
            override fun existsById(id: String): Boolean = db.bookMoodsQueries.existsById(id).executeAsOne()

            override fun softDeleteById(
                id: String,
                revision: Long,
                updatedAt: Long,
                deletedAt: Long,
                clientOpId: String?,
            ): Long =
                db.bookMoodsQueries
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
                db.bookMoodsQueries
                    .selectIdsAboveRevision(cursor, limit) { id, revision -> IdRev(id, revision) }
                    .executeAsList()

            override fun selectIdRevAtMost(cursor: Long): List<IdRev> =
                db.bookMoodsQueries
                    .selectIdRevAtMost(cursor) { id, revision -> IdRev(id, revision) }
                    .executeAsList()
        }

    // Tombstone-inclusive read by synthetic id — pullSince/readPayloads must hydrate
    // soft-deleted rows so clients receive tombstones.
    override fun readPayload(idStr: String): BookMoodSyncPayload? =
        db.bookMoodsQueries
            .selectById(idStr)
            .executeAsOneOrNull()
            ?.toPayload()

    override fun readPayloads(idStrs: List<String>): List<BookMoodSyncPayload> {
        if (idStrs.isEmpty()) return emptyList()
        // SQLite's variable limit (SQLITE_MAX_VARIABLE_NUMBER, 999 by default) caps an
        // `IN (?, ?, …)` list, so batch in chunks of 900 and preserve the requested order.
        val byId =
            idStrs
                .chunked(SQLITE_IN_CHUNK)
                .flatMap { chunk -> db.bookMoodsQueries.selectByIds(chunk).executeAsList() }
                .associateBy { it.id }
        return idStrs.mapNotNull { byId[it]?.toPayload() }
    }

    override fun writePayload(
        value: BookMoodSyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        if (existed) {
            db.bookMoodsQueries.update(
                revision = rev,
                updated_at = now,
                deleted_at = null,
                client_op_id = clientOpId,
                book_id = value.bookId,
                mood_id = value.moodId,
            )
        } else {
            db.bookMoodsQueries.insert(
                // Deterministic re-derivation of the SAME resolution upsert() already made for
                // this write (same natural pair, same candidate, same transaction — no row has
                // been inserted between the two calls, so the natural-pair lookup still misses).
                id = idAsString(BookMoodId(value.bookId, value.moodId, value.id)),
                book_id = value.bookId,
                mood_id = value.moodId,
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
    suspend fun findAllForBook(bookId: String): List<BookMoodSyncPayload> =
        suspendTransaction(db) {
            db.bookMoodsQueries
                .selectByBookId(bookId)
                .executeAsList()
                .sortedBy { it.created_at }
                .map { it.toPayload() }
        }

    /**
     * Returns all non-tombstoned junction rows for [moodId].
     */
    suspend fun findAllForMood(moodId: String): List<BookMoodSyncPayload> =
        suspendTransaction(db) {
            db.bookMoodsQueries
                .selectByMoodId(moodId)
                .executeAsList()
                .map { it.toPayload() }
        }

    /**
     * Returns book IDs for all non-tombstoned junction rows linked to [moodId].
     * Used by [com.calypsan.listenup.server.api.MoodServiceImpl] to collect the
     * set of books affected by a mood deletion.
     */
    suspend fun findBookIdsForMood(moodId: String): List<String> =
        suspendTransaction(db) {
            db.bookMoodsQueries.selectBookIdsForMood(moodId).executeAsList()
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
            val live = db.bookMoodsQueries.selectLiveIdsForBook(bookId).executeAsList()
            tombstoneEach(live)
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
            val live = db.bookMoodsQueries.selectLiveIdsForMood(moodId).executeAsList()
            tombstoneEach(live)
            live.size
        }

    /**
     * Revives the tombstoned junction rows for the books in [bookIds] that were tombstoned at or after
     * [cascadeFloor] — the cascade counterpart to [softDeleteAllForBook], run when a removed folder is
     * re-added so a book's moods return with the book instead of being lost. [cascadeFloor] is the
     * removed folder's own `deleted_at`, flooring the revival exactly as the book/tag revivals are
     * floored. All revives run in ONE transaction; each row gets its own revision bump and an
     * after-commit [SyncEvent.Updated] (deleted_at cleared) so clients reflow the mood as live.
     * Returns the number of rows revived. A no-op (returns 0) when [bookIds] is empty.
     */
    suspend fun reviveAllForBooks(
        bookIds: List<String>,
        cascadeFloor: Long,
    ): Int {
        if (bookIds.isEmpty()) return 0
        return suspendTransaction(db) {
            var count = 0
            for (chunk in bookIds.chunked(SQLITE_IN_CHUNK)) {
                for (row in db.bookMoodsQueries.selectDeletedForBooksSince(chunk, cascadeFloor).executeAsList()) {
                    val rev = nextRevision()
                    val now = clock.now().toEpochMilliseconds()
                    db.bookMoodsQueries.reviveById(revision = rev, updated_at = now, id = row.id)
                    emitAfterCommit(
                        event =
                            SyncEvent.Updated(
                                id = row.id,
                                revision = rev,
                                occurredAt = now,
                                clientOpId = null,
                                payload =
                                    BookMoodSyncPayload(
                                        id = row.id,
                                        bookId = row.book_id,
                                        moodId = row.mood_id,
                                        createdAt = row.created_at,
                                        revision = rev,
                                        deletedAt = null,
                                    ),
                            ),
                    )
                    count++
                }
            }
            count
        }
    }

    /**
     * Tombstones each synthetic id in [syntheticIds] with its own revision bump, inside
     * the caller's open transaction, and registers a per-row after-commit
     * [SyncEvent.Deleted]. The shared body of [softDeleteAllForBook] / [softDeleteAllForMood].
     */
    private fun app.cash.sqldelight.TransactionWithReturn<*>.tombstoneEach(syntheticIds: List<String>) {
        for (syntheticId in syntheticIds) {
            val rev = nextRevision()
            val now = clock.now().toEpochMilliseconds()
            db.bookMoodsQueries.softDeleteById(
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

    /** Maps a generated [Book_moods] row to the wire [BookMoodSyncPayload] DTO. */
    private fun Book_moods.toPayload(): BookMoodSyncPayload =
        BookMoodSyncPayload(
            id = id,
            bookId = book_id,
            moodId = mood_id,
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
