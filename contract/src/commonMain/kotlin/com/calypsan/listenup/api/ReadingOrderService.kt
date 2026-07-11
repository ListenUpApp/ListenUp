package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.readingorder.DiscoveredReadingOrder
import com.calypsan.listenup.api.dto.readingorder.ReadingOrder
import com.calypsan.listenup.api.dto.readingorder.ReadingOrderDetail
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ReadingOrderId
import kotlinx.rpc.annotations.Rpc

/**
 * RPC contract for reading-order lifecycle, membership, and discovery.
 *
 * Reading orders are user-owned, named, ordered, attributed lists of books —
 * personal curation artifacts (e.g. "Cosmere — Chronological") architecturally a
 * near-exact sibling of [ShelfService]. Unlike collections (which support shared
 * access grants), reading orders are owned exclusively by the creating user: only
 * the owner may mutate their reading orders. Other users may discover and view
 * public reading orders via [discoverReadingOrders] and [getReadingOrder], but
 * always through the [com.calypsan.listenup.api.BookAccessPolicy] filter — books
 * the viewer cannot access are silently excluded from the returned list.
 *
 * Three surface categories:
 * - **Own-order observation** — [listMyReadingOrders], [getReadingOrder] (owner
 *   path) read the caller's reading orders and are safe to call repeatedly.
 * - **Own-order mutation** — [createReadingOrder], [updateReadingOrder],
 *   [deleteReadingOrder], [addBookToReadingOrder], [removeBookFromReadingOrder],
 *   [reorderReadingOrderBooks] mutate the caller's reading orders; all fail with
 *   [com.calypsan.listenup.api.error.ReadingOrderError.Forbidden] when called on
 *   another user's reading order.
 * - **Discovery** — [getReadingOrder] (non-owner path) and [discoverReadingOrders]
 *   expose public reading orders with access-filtered book lists; private reading
 *   orders return [com.calypsan.listenup.api.error.ReadingOrderError.NotFound] to
 *   non-owners.
 */
@Rpc
interface ReadingOrderService {
    // ── Own-order mutation ────────────────────────────────────────────────────

    /**
     * Creates a new reading order named [name] owned by the caller and returns it
     * as a [ReadingOrder] summary.
     *
     * [name] must be between 1 and 200 characters; blank or overlong names
     * return [com.calypsan.listenup.api.error.ReadingOrderError.InvalidName].
     *
     * @param name Display name for the new reading order.
     * @param description Optional description of the reading order's theme or purpose.
     * @param attribution Free text — who recommends this order / why.
     * @param isPrivate `true` to create a private reading order visible only to the owner.
     */
    suspend fun createReadingOrder(
        name: String,
        description: String = "",
        attribution: String = "",
        isPrivate: Boolean = false,
    ): AppResult<ReadingOrder>

    /**
     * Updates the display name, description, attribution, and privacy flag of the
     * reading order identified by [readingOrderId] and returns the updated
     * [ReadingOrder] summary.
     *
     * Owner-only. Fails with [com.calypsan.listenup.api.error.ReadingOrderError.NotFound]
     * when no reading order with [readingOrderId] exists,
     * [com.calypsan.listenup.api.error.ReadingOrderError.Forbidden] when the caller is
     * not the owner, or
     * [com.calypsan.listenup.api.error.ReadingOrderError.InvalidName] when [name] is invalid.
     *
     * @param readingOrderId Identifies the reading order to update.
     * @param name New display name.
     * @param description New description.
     * @param attribution New attribution text.
     * @param isPrivate Updated privacy flag.
     */
    suspend fun updateReadingOrder(
        readingOrderId: ReadingOrderId,
        name: String,
        description: String,
        attribution: String,
        isPrivate: Boolean,
    ): AppResult<ReadingOrder>

    /**
     * Soft-deletes the reading order identified by [readingOrderId] and cascade-soft-deletes
     * its book membership rows.
     *
     * Owner-only. Fails with [com.calypsan.listenup.api.error.ReadingOrderError.NotFound]
     * when no reading order with [readingOrderId] exists or
     * [com.calypsan.listenup.api.error.ReadingOrderError.Forbidden] when the caller is not
     * the owner.
     *
     * @param readingOrderId Identifies the reading order to delete.
     */
    suspend fun deleteReadingOrder(readingOrderId: ReadingOrderId): AppResult<Unit>

    /**
     * Adds the book identified by [bookId] to the reading order identified by
     * [readingOrderId].
     *
     * Idempotent: re-adding a book that is already a member (or re-adding a
     * previously removed book) clears any tombstone and succeeds without error.
     * The added book is appended at the end of the current sort order.
     *
     * Owner-only. Returns [com.calypsan.listenup.api.error.ReadingOrderError.NotFound]
     * when the reading order does not exist or [bookId] is not accessible to the owner
     * (prevents adding books the owner cannot see).
     *
     * @param readingOrderId Identifies the target reading order.
     * @param bookId Identifies the book to add.
     */
    suspend fun addBookToReadingOrder(
        readingOrderId: ReadingOrderId,
        bookId: BookId,
    ): AppResult<Unit>

    /**
     * Removes the book identified by [bookId] from the reading order identified by
     * [readingOrderId] by soft-deleting its membership row.
     *
     * Idempotent: removing a book that is not a member succeeds without error.
     *
     * Owner-only. Fails with [com.calypsan.listenup.api.error.ReadingOrderError.NotFound]
     * when the reading order does not exist, or
     * [com.calypsan.listenup.api.error.ReadingOrderError.Forbidden] when the caller is not
     * the owner.
     *
     * @param readingOrderId Identifies the target reading order.
     * @param bookId Identifies the book to remove.
     */
    suspend fun removeBookFromReadingOrder(
        readingOrderId: ReadingOrderId,
        bookId: BookId,
    ): AppResult<Unit>

    /**
     * Replace-sets the book order for the reading order identified by [readingOrderId].
     *
     * [orderedBookIds] must be a permutation of the reading order's current live members.
     * Each book's
     * [sort_order][com.calypsan.listenup.api.sync.ReadingOrderBookSyncPayload.sortOrder]
     * is rewritten to its index position in [orderedBookIds]; every affected row gets
     * its revision bumped.
     *
     * Owner-only. Fails with [com.calypsan.listenup.api.error.ReadingOrderError.NotFound]
     * when the reading order does not exist or
     * [com.calypsan.listenup.api.error.ReadingOrderError.Forbidden] when the caller is not
     * the owner.
     *
     * @param readingOrderId Identifies the reading order to reorder.
     * @param orderedBookIds New ordered list of book IDs.
     */
    suspend fun reorderReadingOrderBooks(
        readingOrderId: ReadingOrderId,
        orderedBookIds: List<BookId>,
    ): AppResult<Unit>

    // ── Observation ──────────────────────────────────────────────────────────

    /**
     * Returns all non-deleted reading orders owned by the caller as a list of
     * [ReadingOrder] summaries, ordered by most-recently-updated first.
     *
     * The caller's own reading orders are synced offline-first via the sync
     * substrate; this RPC is the authoritative source for initial hydration and
     * manual refresh.
     */
    suspend fun listMyReadingOrders(): AppResult<List<ReadingOrder>>

    /**
     * Returns the full [ReadingOrderDetail] for the reading order identified by
     * [readingOrderId].
     *
     * - **Owner:** receives all non-deleted books in sort order,
     *   [ReadingOrderDetail.isOwner] = `true`.
     * - **Non-owner, public order:** receives only books accessible via
     *   [com.calypsan.listenup.api.BookAccessPolicy]; [ReadingOrderDetail.isOwner] = `false`.
     * - **Non-owner, private order:** returns
     *   [com.calypsan.listenup.api.error.ReadingOrderError.NotFound] (existence not revealed).
     *
     * Fails with [com.calypsan.listenup.api.error.ReadingOrderError.NotFound] when the
     * reading order does not exist or has been soft-deleted.
     *
     * @param readingOrderId Identifies the reading order to retrieve.
     */
    suspend fun getReadingOrder(readingOrderId: ReadingOrderId): AppResult<ReadingOrderDetail>

    /**
     * Returns [userId]'s **public** reading orders, each with a book count reflecting
     * only books the **calling viewer** can access; orders with zero accessible books
     * are excluded. Any authenticated user may call this (the view-other-profile
     * surface). Private reading orders are never returned.
     *
     * @param userId Identifies the user whose public reading orders are requested.
     */
    suspend fun getUserReadingOrders(userId: UserId): AppResult<List<ReadingOrder>>

    /**
     * Returns up to [limit] public reading orders owned by other users, each with its
     * book list access-filtered to books the caller can see.
     *
     * Reading orders with zero accessible books are excluded entirely. The caller's own
     * reading orders are never included. [bookCount][ReadingOrder.bookCount] in each
     * result reflects only accessible books, not the owner's full count.
     *
     * @param limit Maximum number of reading orders to return. Clamped server-side. Default 50.
     */
    suspend fun discoverReadingOrders(limit: Int = 50): AppResult<List<DiscoveredReadingOrder>>
}
