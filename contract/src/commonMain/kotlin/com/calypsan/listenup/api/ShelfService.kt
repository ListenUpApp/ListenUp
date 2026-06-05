package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.shelf.DiscoveredShelf
import com.calypsan.listenup.api.dto.shelf.Shelf
import com.calypsan.listenup.api.dto.shelf.ShelfDetail
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ShelfId
import kotlinx.rpc.annotations.Rpc

/**
 * RPC contract for shelf lifecycle, membership, and discovery.
 *
 * Shelves are user-owned, ordered lists of books for personal curation and
 * social discovery. Unlike collections (which support shared access grants),
 * shelves are owned exclusively by the creating user: only the owner may
 * mutate their shelves. Other users may discover and view public shelves via
 * [discoverShelves] and [getShelf], but always through the
 * [com.calypsan.listenup.api.BookAccessPolicy] filter — books the viewer
 * cannot access are silently excluded from the returned list.
 *
 * Three surface categories:
 * - **Own-shelf observation** — [listMyShelves], [getShelf] (owner path) read
 *   the caller's shelves and are safe to call repeatedly.
 * - **Own-shelf mutation** — [createShelf], [updateShelf], [deleteShelf],
 *   [addBookToShelf], [removeBookFromShelf], [reorderShelfBooks] mutate the
 *   caller's shelves; all fail with [com.calypsan.listenup.api.error.ShelfError.Forbidden]
 *   when called on another user's shelf.
 * - **Discovery** — [getShelf] (non-owner path) and [discoverShelves] expose
 *   public shelves with access-filtered book lists; private shelves return
 *   [com.calypsan.listenup.api.error.ShelfError.NotFound] to non-owners.
 *
 * REST mirrors are defined in `com.calypsan.listenup.api.resources.ShelfResources`.
 */
@Rpc
interface ShelfService {
    // ── Own-shelf mutation ────────────────────────────────────────────────────

    /**
     * Creates a new shelf named [name] owned by the caller and returns it as a
     * [Shelf] summary.
     *
     * [name] must be between 1 and 200 characters; blank or overlong names
     * return [com.calypsan.listenup.api.error.ShelfError.InvalidName].
     *
     * @param name Display name for the new shelf.
     * @param description Optional description of the shelf's theme or purpose.
     * @param isPrivate `true` to create a private shelf visible only to the owner.
     */
    suspend fun createShelf(
        name: String,
        description: String = "",
        isPrivate: Boolean = false,
    ): AppResult<Shelf>

    /**
     * Updates the display name, description, and privacy flag of the shelf
     * identified by [shelfId] and returns the updated [Shelf] summary.
     *
     * Owner-only. Fails with [com.calypsan.listenup.api.error.ShelfError.NotFound]
     * when no shelf with [shelfId] exists, [com.calypsan.listenup.api.error.ShelfError.Forbidden]
     * when the caller is not the owner, or
     * [com.calypsan.listenup.api.error.ShelfError.InvalidName] when [name] is invalid.
     *
     * @param shelfId Identifies the shelf to update.
     * @param name New display name.
     * @param description New description.
     * @param isPrivate Updated privacy flag.
     */
    suspend fun updateShelf(
        shelfId: ShelfId,
        name: String,
        description: String,
        isPrivate: Boolean,
    ): AppResult<Shelf>

    /**
     * Soft-deletes the shelf identified by [shelfId] and cascade-soft-deletes
     * its book membership rows.
     *
     * Owner-only. Fails with [com.calypsan.listenup.api.error.ShelfError.NotFound]
     * when no shelf with [shelfId] exists or
     * [com.calypsan.listenup.api.error.ShelfError.Forbidden] when the caller is not
     * the owner.
     *
     * @param shelfId Identifies the shelf to delete.
     */
    suspend fun deleteShelf(shelfId: ShelfId): AppResult<Unit>

    /**
     * Adds the book identified by [bookId] to the shelf identified by [shelfId].
     *
     * Idempotent: re-adding a book that is already a member (or re-adding a
     * previously removed book) clears any tombstone and succeeds without error.
     * The added book is appended at the end of the current sort order.
     *
     * Owner-only. Returns [com.calypsan.listenup.api.error.ShelfError.NotFound]
     * when the shelf does not exist or [bookId] is not accessible to the owner
     * (prevents adding books the owner cannot see).
     *
     * @param shelfId Identifies the target shelf.
     * @param bookId Identifies the book to add.
     */
    suspend fun addBookToShelf(
        shelfId: ShelfId,
        bookId: BookId,
    ): AppResult<Unit>

    /**
     * Removes the book identified by [bookId] from the shelf identified by
     * [shelfId] by soft-deleting its membership row.
     *
     * Idempotent: removing a book that is not a member succeeds without error.
     *
     * Owner-only. Fails with [com.calypsan.listenup.api.error.ShelfError.NotFound]
     * when the shelf does not exist, or
     * [com.calypsan.listenup.api.error.ShelfError.Forbidden] when the caller is not
     * the owner.
     *
     * @param shelfId Identifies the target shelf.
     * @param bookId Identifies the book to remove.
     */
    suspend fun removeBookFromShelf(
        shelfId: ShelfId,
        bookId: BookId,
    ): AppResult<Unit>

    /**
     * Replace-sets the book order for the shelf identified by [shelfId].
     *
     * [orderedBookIds] must be a permutation of the shelf's current live members.
     * Each book's [sort_order][com.calypsan.listenup.api.sync.ShelfBookSyncPayload.sortOrder]
     * is rewritten to its index position in [orderedBookIds] within a single
     * transaction; every affected row gets its revision bumped.
     *
     * Owner-only. Fails with [com.calypsan.listenup.api.error.ShelfError.NotFound]
     * when the shelf does not exist or
     * [com.calypsan.listenup.api.error.ShelfError.Forbidden] when the caller is not
     * the owner.
     *
     * @param shelfId Identifies the shelf to reorder.
     * @param orderedBookIds New ordered list of book IDs.
     */
    suspend fun reorderShelfBooks(
        shelfId: ShelfId,
        orderedBookIds: List<BookId>,
    ): AppResult<Unit>

    // ── Observation ──────────────────────────────────────────────────────────

    /**
     * Returns all non-deleted shelves owned by the caller as a list of [Shelf]
     * summaries, ordered by most-recently-updated first.
     *
     * The caller's own shelves are synced offline-first via the sync substrate;
     * this RPC is the authoritative source for initial hydration and manual refresh.
     */
    suspend fun listMyShelves(): AppResult<List<Shelf>>

    /**
     * Returns the full [ShelfDetail] for the shelf identified by [shelfId].
     *
     * - **Owner:** receives all non-deleted books in sort order, [ShelfDetail.isOwner] = `true`.
     * - **Non-owner, public shelf:** receives only books accessible via
     *   [com.calypsan.listenup.api.BookAccessPolicy]; [ShelfDetail.isOwner] = `false`.
     * - **Non-owner, private shelf:** returns
     *   [com.calypsan.listenup.api.error.ShelfError.NotFound] (existence not revealed).
     *
     * Fails with [com.calypsan.listenup.api.error.ShelfError.NotFound] when the shelf
     * does not exist or has been soft-deleted.
     *
     * @param shelfId Identifies the shelf to retrieve.
     */
    suspend fun getShelf(shelfId: ShelfId): AppResult<ShelfDetail>

    /**
     * Returns [userId]'s **public** shelves, each with a book count reflecting only books the
     * **calling viewer** can access; shelves with zero accessible books are excluded. Any
     * authenticated user may call this (the view-other-profile surface). Private shelves are
     * never returned.
     *
     * @param userId Identifies the user whose public shelves are requested.
     */
    suspend fun getUserShelves(userId: UserId): AppResult<List<Shelf>>

    /**
     * Returns up to [limit] public shelves owned by other users, each with its
     * book list access-filtered to books the caller can see.
     *
     * Shelves with zero accessible books are excluded entirely. The caller's own
     * shelves are never included. [bookCount][Shelf.bookCount] in each result
     * reflects only accessible books, not the owner's full count.
     *
     * @param limit Maximum number of shelves to return. Clamped server-side. Default 50.
     */
    suspend fun discoverShelves(limit: Int = 50): AppResult<List<DiscoveredShelf>>
}
