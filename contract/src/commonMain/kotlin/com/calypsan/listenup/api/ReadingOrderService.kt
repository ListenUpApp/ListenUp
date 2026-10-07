package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.readingorder.ReadingOrderChoice
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ReadingOrderId
import com.calypsan.listenup.core.SeriesId
import kotlinx.rpc.annotations.Rpc

/**
 * RPC contract for reading orders (#962): named, ordered lists of books, each belonging to exactly one
 * series at any level of the hierarchy, and each user's choice of order per series.
 *
 * Every mutation is idempotent on replay, because every client write goes through the outbox. There
 * are no read methods beyond [countReadingOrderFollowers]: orders, their books and the caller's choices
 * reach clients through the `reading_orders`, `reading_order_books` and `reading_order_follows` sync
 * domains, and the cursored pull is the correctness path.
 *
 * Who may do what:
 * - making an order needs the "make reading orders" permission (ROOT/ADMIN hold it implicitly); a
 *   member without it gets [com.calypsan.listenup.api.error.AuthError.PermissionDenied];
 * - changing one needs ROOT/ADMIN, or being its maker while still holding the permission, else
 *   [com.calypsan.listenup.api.error.ReadingOrderError.Forbidden];
 * - following needs nothing.
 *
 * An order whose series is not live (deleted, merged away or purged) is dormant: every write to it is
 * [com.calypsan.listenup.api.error.ReadingOrderError.NotFound], and it comes back with its series.
 */
@Rpc
interface ReadingOrderService {
    /**
     * Makes an order named [name] on [seriesId] under the client-minted [id]. Replaying the same [id] as
     * its maker is Success and changes nothing; an [id] that exists otherwise is `Forbidden`. Fails with
     * `InvalidName`, `NameAlreadyExists`, or `NotFound` when the series is not live.
     */
    suspend fun createReadingOrder(
        id: ReadingOrderId,
        seriesId: SeriesId,
        name: String,
    ): AppResult<Unit>

    /** Renames the order. Renaming to its current name is Success. Fails with `InvalidName` or `NameAlreadyExists`. */
    suspend fun renameReadingOrder(
        id: ReadingOrderId,
        name: String,
    ): AppResult<Unit>

    /**
     * Deletes the order, its memberships, and every user's follow of it (those series inherit again).
     * Deleting a deleted order is `NotFound`; the client's outbox sender treats that as done.
     */
    suspend fun deleteReadingOrder(id: ReadingOrderId): AppResult<Unit>

    /**
     * Appends [bookId] to the order under the client-minted, opaque [membershipId] (SERVER-SYNC-04).
     * Re-adding a member is Success and keeps its place. Fails with `BookOutsideSeries` when the book is
     * in no series of the order's subtree, and `NotFound` when the caller can't see the book.
     */
    suspend fun addBookToReadingOrder(
        id: ReadingOrderId,
        bookId: BookId,
        membershipId: String,
    ): AppResult<Unit>

    /** Takes [bookId] out of the order. Removing a non-member is Success. */
    suspend fun removeBookFromReadingOrder(
        id: ReadingOrderId,
        bookId: BookId,
    ): AppResult<Unit>

    /**
     * Rearranges the order, tolerantly: the listed live members first, in the given order, then every
     * unlisted member in its previous order. Unknown and non-member ids are ignored, so a reorder queued
     * offline still applies after another device added a book. More than 5,000 ids is `InvalidInput`.
     */
    suspend fun reorderReadingOrder(
        id: ReadingOrderId,
        orderedBookIds: List<BookId>,
    ): AppResult<Unit>

    /**
     * Makes [choice] the caller's order on [seriesId]. A user-made order must live on the series itself
     * or one of its ancestors, else `ChoiceUnavailable`. `NotFound` when the series is not live.
     */
    suspend fun chooseReadingOrder(
        seriesId: SeriesId,
        choice: ReadingOrderChoice,
    ): AppResult<Unit>

    /** Clears the caller's choice on [seriesId], so the series inherits again. Idempotent. */
    suspend fun clearReadingOrderChoice(seriesId: SeriesId): AppResult<Unit>

    /**
     * How many users currently follow the order, for the delete confirmation. Online-only; only the
     * order's maker or an admin may ask (else `Forbidden`).
     */
    suspend fun countReadingOrderFollowers(id: ReadingOrderId): AppResult<Int>
}
