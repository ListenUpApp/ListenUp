package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ShelfBookSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.Shelf_books
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Natural-pair identity for `shelf_books` junction rows — the server-internal type the
 * [SqlSyncableRepository] substrate uses to resolve the row's REAL opaque wire id.
 *
 * The wire representation is [ShelfBookSyncPayload], which carries [shelfId] and [bookId] as
 * top-level fields, plus its own opaque `id` (SERVER-SYNC-04: minted at creation, encodes
 * nothing). [candidateWireId] carries that candidate id through to [idAsString] —
 * client-minted for an offline-first create, server-minted otherwise — for the case where no
 * row exists yet for the pair. When a row already exists (live or tombstoned), [idAsString]
 * discards the candidate and returns the EXISTING row's id instead — "the existing row's id
 * wins" on a natural-pair conflict.
 */
data class ShelfBookId(
    val shelfId: String,
    val bookId: String,
    val candidateWireId: String = "",
) {
    fun asString(): String = "$shelfId:$bookId"

    companion object {
        /** Parses a synthetic id string back into its composite parts. */
        fun fromString(s: String): ShelfBookId {
            val colon = s.indexOf(':')
            check(colon > 0) { "Invalid ShelfBookId string: $s" }
            return ShelfBookId(s.substring(0, colon), s.substring(colon + 1))
        }
    }
}

/**
 * SQLDelight syncable repository for the `shelf_books` userScoped junction — the
 * composite-key, user-scoped sibling of [ShelfRepository].
 *
 * `userScoped = true` — junction rows carry the owning `user_id` (the shelf owner)
 * and sync only to that user. Pull/digest route through the substrate's `*ForUser`
 * variants ([SyncableSubstrateQueries.selectIdsAboveRevisionForUser] /
 * [SyncableSubstrateQueries.selectIdRevAtMostForUser]). The `shelf_books.id` column stores an
 * opaque per-row id (SERVER-SYNC-04) the base uses for revision-cursor queries; the natural
 * composite PK `(shelf_id, book_id)` is the write and lookup key. [idAsString] resolves between
 * the two — see [ShelfBookId].
 *
 * `sort_order` is the denormalized display position within the shelf, preserved
 * verbatim across the conversion (Int on the wire, INTEGER in SQLite, mapped at the
 * boundary).
 *
 * Service-layer helpers beyond the base substrate — all route through the
 * substrate's [upsert]/[softDelete] so every mutation bumps a revision and
 * publishes to the change bus:
 *  - [listByShelf] — live junction rows for a shelf, ordered by sort order
 *  - [addBook] — append a book to a shelf (next free sort order), idempotent
 *  - [removeBook] — soft-delete one junction row
 *  - [reorder] — rewrite sort order to match a given book ordering
 *  - [softDeleteByShelf] — cascade soft-delete every junction row of a shelf
 */
class ShelfBookRepository(
    db: ListenUpDatabase,
    bus: ChangeBus,
    registry: SyncRegistry,
    clock: Clock = Clock.System,
) : SqlSyncableRepository<ShelfBookSyncPayload, ShelfBookId>(
        db = db,
        bus = bus,
        registry = registry,
        key = SyncDomains.SHELF_BOOKS,
        clock = clock,
    ) {
    override val userScoped: Boolean = true

    override val ShelfBookSyncPayload.id: ShelfBookId get() = ShelfBookId(shelfId, bookId, id)

    /**
     * Resolves [id] to the REAL stored opaque wire id: an existing row's id if one already
     * exists for the natural pair (live or tombstoned — "the existing row's id wins" on a
     * natural-pair conflict), else [ShelfBookId.candidateWireId] as-is.
     *
     * The candidate is NOT minted here — minting is the call site's job (client-minted for an
     * offline-first create, server-minted otherwise, e.g. [addBook]'s `Uuid.random().toString()`).
     * Minting inside this function would be unsafe: [upsert] calls [idAsString] once to decide
     * `existed` and again inside [writePayload]'s insert branch to compute the row's actual
     * `id` — two calls that must resolve identically within the same transaction. A fresh mint
     * per call would make them diverge, and the second (real) write would never match the first
     * (assumed) id — see [SqlSyncableRepository.upsert]'s `readPayload` immediately after
     * `writePayload`.
     */
    override fun idAsString(id: ShelfBookId): String =
        db.shelfBooksQueries
            .selectIdByNaturalPair(id.shelfId, id.bookId)
            .executeAsOneOrNull()
            ?: id.candidateWireId

    /**
     * [SyncableSubstrateQueries] adapter over the generated [ListenUpDatabase.shelfBooksQueries].
     *
     * Keyed on the synthetic `id` column — the base only ever passes the
     * `"$shelfId:$bookId"` string it gets back from [idAsString], so the substrate never
     * needs to decompose it. The two `*ForUser` methods carry the `user_id = :userId`
     * predicate the base applies for a user-scoped aggregate.
     */
    override val substrate: SyncableSubstrateQueries =
        object : SyncableSubstrateQueries {
            override fun existsById(id: String): Boolean = db.shelfBooksQueries.existsById(id).executeAsOne()

            override fun softDeleteById(
                id: String,
                revision: Long,
                updatedAt: Long,
                deletedAt: Long,
                clientOpId: String?,
            ): Long =
                db.shelfBooksQueries
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
                db.shelfBooksQueries
                    .selectIdsAboveRevision(cursor, limit) { id, revision -> IdRev(id, revision) }
                    .executeAsList()

            override fun selectIdRevAtMost(cursor: Long): List<IdRev> =
                db.shelfBooksQueries
                    .selectIdRevAtMost(cursor) { id, revision -> IdRev(id, revision) }
                    .executeAsList()

            override fun selectIdsAboveRevisionForUser(
                userId: String,
                cursor: Long,
                limit: Long,
            ): List<IdRev> =
                db.shelfBooksQueries
                    .selectIdsAboveRevisionForUser(userId, cursor, limit) { id, revision -> IdRev(id, revision) }
                    .executeAsList()

            override fun selectIdRevAtMostForUser(
                userId: String,
                cursor: Long,
            ): List<IdRev> =
                db.shelfBooksQueries
                    .selectIdRevAtMostForUser(userId, cursor) { id, revision -> IdRev(id, revision) }
                    .executeAsList()
        }

    // Tombstone-inclusive read by synthetic id — pullSince/readPayloads must hydrate
    // soft-deleted rows so clients receive tombstones.
    override fun readPayload(idStr: String): ShelfBookSyncPayload? =
        db.shelfBooksQueries
            .selectById(idStr)
            .executeAsOneOrNull()
            ?.toSyncPayload()

    override fun readPayloads(idStrs: List<String>): List<ShelfBookSyncPayload> {
        if (idStrs.isEmpty()) return emptyList()
        // SQLite's variable limit (SQLITE_MAX_VARIABLE_NUMBER, 999 by default) caps an
        // `IN (?, ?, …)` list, so batch in chunks of 900 and preserve the requested order.
        val byId =
            idStrs
                .chunked(SQLITE_IN_CHUNK)
                .flatMap { chunk -> db.shelfBooksQueries.selectByIds(chunk).executeAsList() }
                .associateBy { it.id }
        return idStrs.mapNotNull { byId[it]?.toSyncPayload() }
    }

    /**
     * Tombstone projection (SERVER-SYNC-04) — see [SqlSyncableRepository.minimizeTombstone].
     * The pull path delivers junction tombstones ungated so every client converges, so the
     * pair itself must not cross the wire: a member who never had access to [shelfId] would
     * otherwise learn the association from the tombstone alone. `id`/`revision`/`deletedAt`/
     * `createdAt`/`sortOrder` survive — identity, sync-discipline, and non-sensitive display
     * ordering only.
     */
    override fun minimizeTombstone(payload: ShelfBookSyncPayload): ShelfBookSyncPayload =
        payload.copy(shelfId = "", bookId = "")

    override fun writePayload(
        value: ShelfBookSyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        if (existed) {
            db.shelfBooksQueries.update(
                sort_order = value.sortOrder.toLong(),
                revision = rev,
                updated_at = now,
                deleted_at = null,
                client_op_id = clientOpId,
                shelf_id = value.shelfId,
                book_id = value.bookId,
            )
        } else {
            db.shelfBooksQueries.insert(
                // Deterministic re-derivation of the SAME resolution upsert() already made for
                // this write (same natural pair, same candidate, same transaction — no row has
                // been inserted between the two calls, so the natural-pair lookup still misses).
                id = idAsString(ShelfBookId(value.shelfId, value.bookId, value.id)),
                user_id = requireNotNull(userId) { "ShelfBookRepository.writePayload requires a userId" },
                shelf_id = value.shelfId,
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

    /** Returns all live (non-tombstoned) junction rows for [shelfId], ordered by sort order. */
    suspend fun listByShelf(shelfId: String): List<ShelfBookSyncPayload> =
        suspendTransaction(db) {
            db.shelfBooksQueries
                .selectByShelf(shelfId)
                .executeAsList()
                .map { it.toSyncPayload() }
        }

    /**
     * Appends [bookId] to [shelfId] at the next free sort order, owned by [userId].
     *
     * Idempotent: re-adding a book already live on the shelf is a no-op that returns
     * the existing row unchanged (no duplicate, no spurious revision bump). A
     * previously-removed book is resurrected at the end of the shelf. Routes through
     * the substrate's [upsert], so the write bumps a revision and publishes.
     */
    suspend fun addBook(
        shelfId: String,
        bookId: String,
        userId: String,
    ): AppResult<ShelfBookSyncPayload> {
        val existingLive =
            suspendTransaction(db) {
                db.shelfBooksQueries
                    .selectLiveByShelfAndBook(shelfId, bookId)
                    .executeAsOneOrNull()
                    ?.toSyncPayload()
            }
        if (existingLive != null) return AppResult.Success(existingLive)

        val nextSortOrder = nextSortOrderFor(shelfId)
        return upsert(
            ShelfBookSyncPayload(
                id = Uuid.random().toString(),
                shelfId = shelfId,
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

    /** Soft-deletes the junction row for `(shelfId, bookId)`, owned by [userId]. */
    suspend fun removeBook(
        shelfId: String,
        bookId: String,
        userId: String,
    ): AppResult<Unit> = softDelete(ShelfBookId(shelfId, bookId), userId = userId)

    /**
     * Rewrites every junction row's sort order in [shelfId] to its index in
     * [orderedBookIds], owned by [userId]. Each touched row routes through the
     * substrate's [upsert], bumping a revision and publishing — so the reorder
     * propagates to clients row-by-row. Books on the shelf but absent from
     * [orderedBookIds] are left untouched.
     */
    suspend fun reorder(
        shelfId: String,
        orderedBookIds: List<String>,
        userId: String,
    ): AppResult<Unit> {
        val live = listByShelf(shelfId).associateBy { it.bookId }
        orderedBookIds.forEachIndexed { index, bookId ->
            val current = live[bookId] ?: return@forEachIndexed
            if (current.sortOrder == index) return@forEachIndexed
            val result = upsert(current.copy(sortOrder = index), userId = userId)
            if (result is AppResult.Failure) return result
        }
        return AppResult.Success(Unit)
    }

    /**
     * Cascade soft-deletes every live junction row for [shelfId], owned by [userId].
     * Used as a step in shelf deletion — each row gets its own revision bump and
     * tombstone publication via the substrate's [softDelete]. Returns the number of
     * rows tombstoned.
     */
    suspend fun softDeleteByShelf(
        shelfId: String,
        userId: String,
    ): Int {
        val live = listByShelf(shelfId)
        for (row in live) {
            softDelete(ShelfBookId(shelfId, row.bookId), userId = userId)
        }
        return live.size
    }

    /** Returns the next free sort order for [shelfId] — `max(sortOrder) + 1` over live rows, or 0. */
    private suspend fun nextSortOrderFor(shelfId: String): Int =
        suspendTransaction(db) {
            db.shelfBooksQueries
                .nextSortOrderForShelf(shelfId)
                .executeAsOne()
                .toInt()
        }

    /** Maps a generated [Shelf_books] row to the wire [ShelfBookSyncPayload] DTO (drops `user_id`). */
    private fun Shelf_books.toSyncPayload(): ShelfBookSyncPayload =
        ShelfBookSyncPayload(
            id = id,
            shelfId = shelf_id,
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
