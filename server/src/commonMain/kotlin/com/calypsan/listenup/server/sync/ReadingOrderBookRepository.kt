package com.calypsan.listenup.server.sync

import app.cash.sqldelight.TransactionWithReturn
import app.cash.sqldelight.db.SqlDriver
import com.calypsan.listenup.api.sync.ReadingOrderBookSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.Reading_order_books
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlin.time.Clock
import kotlinx.coroutines.currentCoroutineContext

/**
 * Natural-pair identity for a `reading_order_books` row. [candidateWireId] carries the client-minted
 * opaque id through to [ReadingOrderBookRepository.idAsString] for a pair that has no row yet; when a
 * row exists (live or tombstoned) its stored id wins (SERVER-SYNC-04), so re-adding a removed book
 * revives the same row under the same id.
 */
data class ReadingOrderBookKey(
    val readingOrderId: String,
    val bookId: String,
    val candidateWireId: String = "",
)

/**
 * SQLDelight syncable repository for reading-order membership (#962) — the `book_tags` junction
 * template: natural PK `(reading_order_id, book_id)`, an opaque wire id, per-row access-gated on
 * `book_id` through the injected [driver], and tombstones minimized so a member never learns which
 * order held a book they can't see.
 *
 * Multi-row writes ([rewritePositions], [tombstoneAllForOrder]) run in ONE transaction, each row with
 * its own revision and after-commit event.
 */
class ReadingOrderBookRepository(
    db: ListenUpDatabase,
    bus: ChangeBus,
    registry: SyncRegistry,
    /** Required for the access-filtered read path: rows are visible iff their book is. */
    override val driver: SqlDriver,
    clock: Clock = Clock.System,
) : SqlSyncableRepository<ReadingOrderBookSyncPayload, ReadingOrderBookKey>(
        db = db,
        bus = bus,
        registry = registry,
        key = SyncDomains.READING_ORDER_BOOKS,
        clock = clock,
    ) {
    override val ReadingOrderBookSyncPayload.id: ReadingOrderBookKey
        get() = ReadingOrderBookKey(readingOrderId, bookId, id)

    /**
     * The REAL stored id for the pair, or the candidate when no row exists yet. Never mints: [upsert]
     * resolves this twice in one transaction (existence, then insert), and both must agree — see
     * [BookTagRepository.idAsString].
     */
    override fun idAsString(id: ReadingOrderBookKey): String =
        db.readingOrderBooksQueries
            .selectIdByNaturalPair(reading_order_id = id.readingOrderId, book_id = id.bookId)
            .executeAsOneOrNull()
            ?: id.candidateWireId

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
        }

    override fun readPayload(idStr: String): ReadingOrderBookSyncPayload? =
        db.readingOrderBooksQueries
            .selectById(idStr)
            .executeAsOneOrNull()
            ?.toPayload()

    override fun readPayloads(idStrs: List<String>): List<ReadingOrderBookSyncPayload> {
        if (idStrs.isEmpty()) return emptyList()
        val byId =
            idStrs
                .chunked(SQLITE_IN_CHUNK)
                .flatMap { chunk -> db.readingOrderBooksQueries.selectByIds(chunk).executeAsList() }
                .associateBy { it.id }
        return idStrs.mapNotNull { byId[it]?.toPayload() }
    }

    /** Tombstones cross the wire ungated, so the pair is blanked (SERVER-SYNC-04). */
    override fun minimizeTombstone(payload: ReadingOrderBookSyncPayload): ReadingOrderBookSyncPayload =
        payload.copy(readingOrderId = "", bookId = "")

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
                position = value.position.toLong(),
                revision = rev,
                updated_at = now,
                deleted_at = null,
                client_op_id = clientOpId,
                reading_order_id = value.readingOrderId,
                book_id = value.bookId,
            )
        } else {
            db.readingOrderBooksQueries.insert(
                // The same resolution upsert() already made for this write, in the same transaction.
                id = idAsString(ReadingOrderBookKey(value.readingOrderId, value.bookId, value.id)),
                reading_order_id = value.readingOrderId,
                book_id = value.bookId,
                position = value.position.toLong(),
                created_at = now,
                updated_at = now,
                revision = rev,
                deleted_at = null,
                client_op_id = clientOpId,
            )
        }
    }

    /** Live members of [orderId], first to last. */
    suspend fun liveMembers(orderId: String): List<ReadingOrderBookSyncPayload> =
        suspendTransaction(db) {
            db.readingOrderBooksQueries
                .selectLiveForOrder(orderId)
                .executeAsList()
                .map { it.toPayload() }
        }

    /** One past the highest live position in [orderId], or 0 when it has no live members. */
    suspend fun nextPosition(orderId: String): Int =
        suspendTransaction(db) {
            db.readingOrderBooksQueries
                .nextPosition(orderId)
                .executeAsOne()
                .toInt()
        }

    /**
     * Rearranges [orderId]'s live members into [arrangement] (a function of the current members, first to
     * last, returning the new sequence of their book ids), and rewrites — with a fresh revision and event
     * each — only the rows whose position changes. One transaction, so a concurrent add can't interleave.
     * Returns how many rows were rewritten.
     */
    suspend fun rewritePositions(
        orderId: String,
        arrangement: (List<String>) -> List<String>,
    ): Int {
        val suppressed = currentCoroutineContext()[FirehoseSuppressed.Key] != null
        val capture = currentCoroutineContext()[FrameCapture.Key]
        return suspendTransaction(db) {
            val live =
                db.readingOrderBooksQueries
                    .selectLiveForOrder(orderId)
                    .executeAsList()
                    .map { it.toPayload() }
            val byBook = live.associateBy { it.bookId }
            var rewritten = 0
            arrangement(live.map { it.bookId }).forEachIndexed { position, bookId ->
                val member = byBook[bookId] ?: return@forEachIndexed
                if (member.position == position) return@forEachIndexed
                val (_, event) = upsertEventInOpenTransaction(member.copy(position = position), suppressed)
                if (!suppressed) captureAfterCommit(capture, event)
                rewritten++
            }
            rewritten
        }
    }

    /**
     * Tombstones every live member of [orderId] inside the caller's open transaction — the membership half
     * of an order's delete, which must commit together with the order's own tombstone. Each row gets its
     * own revision and after-commit event.
     */
    internal fun TransactionWithReturn<*>.tombstoneAllForOrder(
        orderId: String,
        suppressed: Boolean,
    ): Int {
        val live = db.readingOrderBooksQueries.selectLiveForOrder(orderId).executeAsList()
        for (row in live) {
            softDeleteInOpenTransaction(ReadingOrderBookKey(row.reading_order_id, row.book_id, row.id), suppressed)
        }
        return live.size
    }

    private fun Reading_order_books.toPayload(): ReadingOrderBookSyncPayload =
        ReadingOrderBookSyncPayload(
            id = id,
            readingOrderId = reading_order_id,
            bookId = book_id,
            // `position` is INTEGER in SQLite → Long in SQLDelight; the wire field is Int.
            position = position.toInt(),
            revision = revision,
            updatedAt = updated_at,
            createdAt = created_at,
            deletedAt = deleted_at,
        )

    private companion object {
        /** Chunk size for `IN (…)` reads, under SQLite's default 999-variable cap. */
        const val SQLITE_IN_CHUNK = 900
    }
}
