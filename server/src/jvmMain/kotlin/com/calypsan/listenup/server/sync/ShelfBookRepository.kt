package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ShelfBookSyncPayload
import com.calypsan.listenup.server.db.ShelfBooksTable
import kotlin.time.Clock
import kotlinx.serialization.KSerializer
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Composite key for `shelf_books` junction rows.
 *
 * The wire representation is [ShelfBookSyncPayload], which carries [shelfId] and
 * [bookId] as top-level fields. [asString] produces the synthetic
 * `"$shelfId:$bookId"` key stored in [ShelfBooksTable.id] and used by the
 * [SyncableRepository] substrate for revision-cursor identity.
 */
data class ShelfBookId(
    val shelfId: String,
    val bookId: String,
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
 * Syncable repository for the `shelf_books` userScoped junction.
 *
 * `userScoped = true` — junction rows carry the owning `user_id` and sync only
 * to that user. The [ShelfBooksTable.id] column stores the synthetic
 * `"$shelfId:$bookId"` key the base class uses for revision-cursor queries; the
 * natural composite PK `(shelf_id, book_id)` is the write and lookup key.
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
    db: Database,
    bus: ChangeBus,
    registry: SyncRegistry,
    clock: Clock = Clock.System,
) : SyncableRepository<ShelfBookSyncPayload, ShelfBookId>(
        db = db,
        table = ShelfBooksTable,
        bus = bus,
        registry = registry,
        domainName = "shelf_books",
        clock = clock,
    ) {
    override val userScoped: Boolean = true

    override val elementSerializer: KSerializer<ShelfBookSyncPayload> = ShelfBookSyncPayload.serializer()

    override val ShelfBookSyncPayload.id: ShelfBookId get() = ShelfBookId(shelfId, bookId)

    override fun ShelfBookSyncPayload.revisionOf(): Long = revision

    /** Returns the synthetic [ShelfBooksTable.id] column for the base-class WHERE clauses. */
    override fun idColumn(): Column<String> = ShelfBooksTable.id

    /** Converts a [ShelfBookId] to the stored `"$shelfId:$bookId"` string. */
    override fun idAsString(id: ShelfBookId): String = id.asString()

    override suspend fun readPayload(idStr: String): ShelfBookSyncPayload? {
        val key = ShelfBookId.fromString(idStr)
        return ShelfBooksTable
            .selectAll()
            .where {
                (ShelfBooksTable.shelfId eq key.shelfId) and
                    (ShelfBooksTable.bookId eq key.bookId)
            }.firstOrNull()
            ?.toSyncPayload()
    }

    override suspend fun writePayload(
        value: ShelfBookSyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        requireNotNull(userId) { "ShelfBookRepository.writePayload requires a userId" }
        if (existed) {
            ShelfBooksTable.update({
                (ShelfBooksTable.shelfId eq value.shelfId) and
                    (ShelfBooksTable.bookId eq value.bookId)
            }) { stmt ->
                stmt[ShelfBooksTable.sortOrder] = value.sortOrder
                stmt[ShelfBooksTable.revision] = rev
                stmt[ShelfBooksTable.updatedAt] = now
                stmt[ShelfBooksTable.deletedAt] = null
                stmt[ShelfBooksTable.clientOpId] = clientOpId
            }
        } else {
            ShelfBooksTable.insert { stmt ->
                stmt[ShelfBooksTable.id] = ShelfBookId(value.shelfId, value.bookId).asString()
                stmt[ShelfBooksTable.userId] = userId
                stmt[ShelfBooksTable.shelfId] = value.shelfId
                stmt[ShelfBooksTable.bookId] = value.bookId
                stmt[ShelfBooksTable.sortOrder] = value.sortOrder
                stmt[ShelfBooksTable.revision] = rev
                stmt[ShelfBooksTable.createdAt] = now
                stmt[ShelfBooksTable.updatedAt] = now
                stmt[ShelfBooksTable.deletedAt] = null
                stmt[ShelfBooksTable.clientOpId] = clientOpId
            }
        }
    }

    /** Returns all live (non-tombstoned) junction rows for [shelfId], ordered by sort order. */
    suspend fun listByShelf(shelfId: String): List<ShelfBookSyncPayload> =
        suspendTransaction(db) {
            ShelfBooksTable
                .selectAll()
                .where { (ShelfBooksTable.shelfId eq shelfId) and ShelfBooksTable.deletedAt.isNull() }
                .orderBy(ShelfBooksTable.sortOrder, SortOrder.ASC)
                .map { row -> row.toSyncPayload() }
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
                ShelfBooksTable
                    .selectAll()
                    .where {
                        (ShelfBooksTable.shelfId eq shelfId) and
                            (ShelfBooksTable.bookId eq bookId) and
                            ShelfBooksTable.deletedAt.isNull()
                    }.firstOrNull()
                    ?.toSyncPayload()
            }
        if (existingLive != null) return AppResult.Success(existingLive)

        val nextSortOrder = nextSortOrderFor(shelfId)
        return upsert(
            ShelfBookSyncPayload(
                id = ShelfBookId(shelfId, bookId).asString(),
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
            val maxExpr = ShelfBooksTable.sortOrder.max()
            ShelfBooksTable
                .select(maxExpr)
                .where { (ShelfBooksTable.shelfId eq shelfId) and ShelfBooksTable.deletedAt.isNull() }
                .firstOrNull()
                ?.get(maxExpr)
                ?.let { it + 1 }
                ?: 0
        }

    private fun org.jetbrains.exposed.v1.core.ResultRow.toSyncPayload(): ShelfBookSyncPayload =
        ShelfBookSyncPayload(
            id = this[ShelfBooksTable.id],
            shelfId = this[ShelfBooksTable.shelfId],
            bookId = this[ShelfBooksTable.bookId],
            sortOrder = this[ShelfBooksTable.sortOrder],
            revision = this[ShelfBooksTable.revision],
            updatedAt = this[ShelfBooksTable.updatedAt],
            createdAt = this[ShelfBooksTable.createdAt],
            deletedAt = this[ShelfBooksTable.deletedAt],
        )
}
