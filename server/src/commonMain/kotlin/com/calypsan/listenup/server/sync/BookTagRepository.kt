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
 * Natural-pair identity for `book_tags` junction rows — the server-internal type the
 * [SqlSyncableRepository] substrate uses to resolve the row's REAL opaque wire id.
 *
 * The wire representation is [BookTagSyncPayload], which carries [bookId] and [tagId] as
 * top-level fields, plus its own opaque `id` (SERVER-SYNC-04: minted at creation, encodes
 * nothing). [candidateWireId] carries that candidate id through to [idAsString] —
 * client-minted for an offline-first create, server-minted otherwise — for the case where no
 * row exists yet for the pair. When a row already exists (live or tombstoned), [idAsString]
 * discards the candidate and returns the EXISTING row's id instead — "the existing row's id
 * wins" on a natural-pair conflict.
 */
data class BookTagId(
    val bookId: String,
    val tagId: String,
    val candidateWireId: String = "",
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
 * Extends [SqlSyncableRepository] with composite-key awareness. The `book_tags.id` column
 * stores an opaque per-row id (SERVER-SYNC-04) the base uses for revision-cursor queries; the
 * natural composite PK `(book_id, tag_id)` is the write and lookup key. [idAsString] resolves
 * between the two — see [BookTagId].
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
    override val BookTagSyncPayload.id: BookTagId get() = BookTagId(bookId, tagId, id)

    /**
     * Resolves [id] to the REAL stored opaque wire id: an existing row's id if one already
     * exists for the natural pair (live or tombstoned — "the existing row's id wins" on a
     * natural-pair conflict), else [BookTagId.candidateWireId] as-is.
     *
     * The candidate is NOT minted here — minting is the call site's job (client-minted for an
     * offline-first create, server-minted otherwise, e.g. `Uuid.random().toString()` in
     * `TagServiceImpl`/`BookTagWriter`). Minting inside this function would be unsafe: [upsert]
     * calls [idAsString] once to decide `existed` and again inside [writePayload]'s insert
     * branch to compute the row's actual `id` — two calls that must resolve identically within
     * the same transaction. A fresh mint per call would make them diverge, and the second
     * (real) write would never match the first (assumed) id — see
     * [SqlSyncableRepository.upsert]'s `readPayload` immediately after `writePayload`.
     */
    override fun idAsString(id: BookTagId): String =
        db.bookTagsQueries
            .selectIdByNaturalPair(id.bookId, id.tagId)
            .executeAsOneOrNull()
            ?: id.candidateWireId

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

    /**
     * Tombstone projection (SERVER-SYNC-04) — see [SqlSyncableRepository.minimizeTombstone].
     * The pull path delivers junction tombstones ungated so every client converges, so the
     * pair itself must not cross the wire: a member who never had access to [bookId] would
     * otherwise learn the association from the tombstone alone. `id`/`revision`/`deletedAt`/
     * `createdAt` survive — identity and sync-discipline fields only.
     */
    override fun minimizeTombstone(payload: BookTagSyncPayload): BookTagSyncPayload = payload.copy(bookId = "", tagId = "")

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
                // Deterministic re-derivation of the SAME resolution upsert() already made for
                // this write (same natural pair, same candidate, same transaction — no row has
                // been inserted between the two calls, so the natural-pair lookup still misses).
                id = idAsString(BookTagId(value.bookId, value.tagId, value.id)),
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
     * Returns all non-tombstoned junction rows for each id in [bookIds], grouped by
     * book id, each book's rows ordered by `created_at` — the batched equivalent of
     * calling [findAllForBook] per id. One round-trip per 900-id chunk (SQLite's
     * `SQLITE_MAX_VARIABLE_NUMBER` cap). Books with no live junctions are absent
     * from the map.
     */
    suspend fun findAllForBooks(bookIds: List<String>): Map<String, List<BookTagSyncPayload>> {
        if (bookIds.isEmpty()) return emptyMap()
        return suspendTransaction(db) {
            bookIds
                .chunked(SQLITE_IN_CHUNK)
                .flatMap { chunk -> db.bookTagsQueries.selectByBookIds(chunk).executeAsList() }
                .sortedBy { it.created_at }
                .groupBy { it.book_id }
                .mapValues { (_, rows) -> rows.map { it.toPayload() } }
        }
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
     * Revives the tombstoned junction rows for the books in [bookIds] that were tombstoned at or after
     * [cascadeFloor] — the cascade counterpart to [softDeleteAllForBook], run when a removed folder is
     * re-added so a book's user tags return with the book instead of being lost. [cascadeFloor] is the
     * removed folder's own `deleted_at`: the remove cascade tombstones the folder, then its books, then
     * their tag junctions at the same instant, so a genuine folder-removal tombstone has
     * `deleted_at >= cascadeFloor`, while a tag the user removed MANUALLY before the folder was removed
     * is an older tombstone and stays dead. Symmetric with the book zombie-exclusion floor. All revives
     * run in ONE transaction; each row gets its own revision bump and an after-commit
     * [SyncEvent.Updated] (deleted_at cleared) so clients reflow the tag as live. Returns the number of
     * rows revived. A no-op (returns 0) when [bookIds] is empty or no qualifying tombstones exist.
     */
    suspend fun reviveAllForBooks(
        bookIds: List<String>,
        cascadeFloor: Long,
    ): Int {
        if (bookIds.isEmpty()) return 0
        return suspendTransaction(db) {
            var count = 0
            for (chunk in bookIds.chunked(SQLITE_IN_CHUNK)) {
                for (row in db.bookTagsQueries.selectDeletedForBooksSince(chunk, cascadeFloor).executeAsList()) {
                    val rev = nextRevision()
                    val now = clock.now().toEpochMilliseconds()
                    db.bookTagsQueries.reviveById(revision = rev, updated_at = now, id = row.id)
                    emitAfterCommit(
                        event =
                            SyncEvent.Updated(
                                id = row.id,
                                revision = rev,
                                occurredAt = now,
                                clientOpId = null,
                                payload =
                                    BookTagSyncPayload(
                                        id = row.id,
                                        bookId = row.book_id,
                                        tagId = row.tag_id,
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
            id = id,
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
