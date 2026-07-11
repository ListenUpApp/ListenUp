@file:MustUseReturnValues

package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.ReadingOrder
import com.calypsan.listenup.client.domain.model.ReadingOrderDetail
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ReadingOrderId
import kotlinx.coroutines.flow.Flow

/**
 * Repository contract for reading-order operations — user-owned, named, ordered,
 * attributed lists of books (Story World Stage 1).
 *
 * **Read model:** own reading orders are mirrored into Room by the sync engine
 * and observed reactively ([observeMyReadingOrders], [observeById]). Discovery of
 * other users' orders is an on-demand RPC read ([discoverReadingOrders]) — those
 * orders are never persisted locally.
 *
 * **Write model (Integration Foundations §5.3):** metadata updates and membership
 * mutations (add/remove/reorder) are **offline-first** — an optimistic Room write
 * plus a durable outbox op in one transaction; the SSE echo confirms/reconciles.
 * [createReadingOrder] and [deleteReadingOrder] stay direct RPC: create needs the
 * server-minted id, and deletes are online by standing product decision.
 *
 * **Follow-state (§5.4):** [observeActiveReadingOrder] / [setActiveReadingOrder]
 * carry the per-series active reading order — the personal spoiler clock. The
 * setter routes through the same offline-first path.
 *
 * Part of the domain layer — implementations live in the data layer.
 */
interface ReadingOrderRepository {
    /**
     * Observe the caller's own reading orders, ordered by most recently updated.
     *
     * @return Flow emitting the list of the caller's reading orders
     */
    fun observeMyReadingOrders(): Flow<List<ReadingOrder>>

    /**
     * Observe a single reading order by ID.
     *
     * @param id The reading-order ID
     * @return Flow emitting the reading order or null
     */
    fun observeById(id: ReadingOrderId): Flow<ReadingOrder?>

    /**
     * Get a reading order by ID synchronously from the local mirror.
     *
     * @param id The reading-order ID
     * @return the reading order if found, null otherwise
     */
    suspend fun getById(id: ReadingOrderId): ReadingOrder?

    /**
     * Get a specific user's public reading orders (view-other-profile surface).
     *
     * On-demand RPC read; book counts reflect only books accessible to the caller.
     * Private orders are never returned.
     *
     * @param userId The target user's ID
     * @return [AppResult.Success] with the user's public orders, or [AppResult.Failure] on RPC error
     */
    suspend fun getUserReadingOrders(userId: String): AppResult<List<ReadingOrder>>

    /**
     * Discover public reading orders owned by other users.
     *
     * On-demand RPC read; book counts reflect only books accessible to the caller.
     *
     * @return [AppResult.Success] with the discovered orders, or [AppResult.Failure] on RPC error
     */
    suspend fun discoverReadingOrders(): AppResult<List<ReadingOrder>>

    /**
     * Get full reading-order detail including its ordered books from the server.
     *
     * Owner receives all books; a non-owner receives the access-filtered set.
     *
     * @param id The reading order to fetch
     * @return [AppResult.Success] with the detail, or [AppResult.Failure] on RPC error
     */
    suspend fun getReadingOrderDetail(id: ReadingOrderId): AppResult<ReadingOrderDetail>

    /**
     * Create a new reading order (direct RPC — the server mints the id; the
     * created order is optimistically mirrored into Room on success).
     *
     * @param name The reading-order name
     * @param description Optional description
     * @param attribution Free text — who recommends this order / why
     * @param isPrivate Whether the order is visible only to the owner
     * @return [AppResult.Success] with the created order, or [AppResult.Failure] on RPC error
     */
    suspend fun createReadingOrder(
        name: String,
        description: String?,
        attribution: String?,
        isPrivate: Boolean,
    ): AppResult<ReadingOrder>

    /**
     * Update a reading order's metadata — **offline-first**: optimistic Room write
     * + durable outbox op; replays on reconnect.
     *
     * @param id The reading order to update
     * @param name The new name
     * @param description The new description (null to clear)
     * @param attribution The new attribution (null to clear)
     * @param isPrivate The new privacy flag
     */
    suspend fun updateReadingOrder(
        id: ReadingOrderId,
        name: String,
        description: String?,
        attribution: String?,
        isPrivate: Boolean,
    ): AppResult<Unit>

    /**
     * Delete a reading order (direct RPC — deletes are online by standing product
     * decision; the SSE tombstone echo removes the local mirror rows).
     *
     * @param id The reading order to delete
     */
    suspend fun deleteReadingOrder(id: ReadingOrderId): AppResult<Unit>

    /**
     * Add books to a reading order, appended at the end of the current sort order —
     * **offline-first**: one optimistic junction row + outbox op per book.
     *
     * @param id The reading order to add to
     * @param bookIds The books to add
     */
    suspend fun addBooksToReadingOrder(
        id: ReadingOrderId,
        bookIds: List<BookId>,
    ): AppResult<Unit>

    /**
     * Remove a book from a reading order — **offline-first**: optimistic local
     * tombstone + outbox op.
     *
     * @param id The reading order to remove from
     * @param bookId The book to remove
     */
    suspend fun removeBookFromReadingOrder(
        id: ReadingOrderId,
        bookId: BookId,
    ): AppResult<Unit>

    /**
     * Reorder the books in a reading order — **offline-first**: optimistic local
     * sort-order rewrite + one outbox op carrying the full ordering.
     *
     * [orderedBookIds] is the new full ordering of the order's live members.
     *
     * @param id The reading order to reorder
     * @param orderedBookIds The books in their new order
     */
    suspend fun reorderBooks(
        id: ReadingOrderId,
        orderedBookIds: List<BookId>,
    ): AppResult<Unit>

    /**
     * Observe the caller's active reading order for [seriesId] — the per-series
     * spoiler clock (Integration Foundations §5.4). Emits null when no order is
     * followed (the per-book frontier floor).
     *
     * @param seriesId The series whose follow-state is observed
     */
    fun observeActiveReadingOrder(seriesId: String): Flow<ReadingOrderId?>

    /**
     * Set (or clear, with null) the caller's active reading order for [seriesId]
     * — **offline-first**: optimistic Room write + durable outbox op.
     *
     * @param seriesId The series whose follow-state is being set
     * @param readingOrderId The order to follow, or null to reset to the frontier floor
     */
    suspend fun setActiveReadingOrder(
        seriesId: String,
        readingOrderId: ReadingOrderId?,
    ): AppResult<Unit>
}
