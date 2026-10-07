package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.error.ReadingOrderError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.api.sync.ReadingOrderBookSyncPayload
import com.calypsan.listenup.api.sync.ReadingOrderSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.ReadingOrderBookKey
import com.calypsan.listenup.server.sync.ReadingOrderBookRepository

/** Most book ids one reorder may name — a bound on the RPC frame. */
internal const val MAX_BOOKS_PER_READING_ORDER_REORDER = 5_000

/**
 * The membership half of [ReadingOrderServiceImpl] (#962): subtree-only adds, idempotent removes and the
 * tolerant reorder. Callers have already passed the edit gate, so nothing here checks who may edit.
 */
internal class ReadingOrderMembership(
    private val members: ReadingOrderBookRepository,
    private val seriesRepo: SeriesRepository,
    private val sqlDb: ListenUpDatabase,
    private val accessPolicy: BookAccessPolicy,
) {
    /**
     * Appends [bookId] to [order] under [membershipId]. The book must be one [caller] can see (else
     * NotFound, so its existence never leaks) and must sit in a series of the order's subtree. A book
     * already in the order keeps its place; a removed one comes back at the end.
     */
    suspend fun add(
        order: ReadingOrderSyncPayload,
        caller: UserPrincipal,
        bookId: BookId,
        membershipId: String,
    ): AppResult<Unit> {
        if (!accessPolicy.canAccess(caller.userId.value, caller.role, bookId.value)) {
            return AppResult.Failure(ReadingOrderError.NotFound(debugInfo = "book=${bookId.value}"))
        }
        val subtree = seriesRepo.liveTree().subtreeOf(order.seriesId)
        val bookSeries =
            suspendTransaction(sqlDb) {
                sqlDb.bookSeriesMembershipsQueries.seriesIdsForBook(bookId.value).executeAsList()
            }
        if (bookSeries.none { it in subtree }) {
            return AppResult.Failure(ReadingOrderError.BookOutsideSeries(debugInfo = "book=${bookId.value}"))
        }
        if (members.liveMembers(order.id).any { it.bookId == bookId.value }) return AppResult.Success(Unit)
        return members
            .upsert(
                ReadingOrderBookSyncPayload(
                    id = membershipId,
                    readingOrderId = order.id,
                    bookId = bookId.value,
                    position = members.nextPosition(order.id),
                    revision = 0,
                    updatedAt = 0,
                    createdAt = 0,
                    deletedAt = null,
                ),
            ).map { }
    }

    /** Takes [bookId] out of [order]. A book that isn't in it is already where the caller wants it. */
    suspend fun remove(
        order: ReadingOrderSyncPayload,
        bookId: BookId,
    ): AppResult<Unit> {
        if (members.liveMembers(order.id).none { it.bookId == bookId.value }) return AppResult.Success(Unit)
        return members.softDelete(ReadingOrderBookKey(order.id, bookId.value))
    }

    /** Rearranges [order] by [tolerantOrder]; only rows whose position changes are rewritten. */
    suspend fun reorder(
        order: ReadingOrderSyncPayload,
        orderedBookIds: List<BookId>,
    ): AppResult<Unit> {
        if (orderedBookIds.size > MAX_BOOKS_PER_READING_ORDER_REORDER) {
            return AppResult.Failure(ReadingOrderError.InvalidInput(debugInfo = "reorder of ${orderedBookIds.size} ids"))
        }
        val requested = orderedBookIds.map { it.value }
        members.rewritePositions(order.id) { current -> tolerantOrder(current, requested) }
        return AppResult.Success(Unit)
    }
}

/**
 * The [requested] ids that are [current] members, in the requested order, then every unlisted member in
 * its current order. Unknown ids and repeats are ignored, so a reorder queued offline still applies after
 * another device added or removed a book — a strict permutation would dead-letter it.
 */
internal fun tolerantOrder(
    current: List<String>,
    requested: List<String>,
): List<String> {
    val currentSet = current.toSet()
    val listed = requested.filter { it in currentSet }.distinct()
    val listedSet = listed.toSet()
    return listed + current.filterNot { it in listedSet }
}
