@file:MustUseReturnValues

package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.Shelf
import com.calypsan.listenup.client.domain.model.ShelfDetail
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ShelfId
import kotlinx.coroutines.flow.Flow

/**
 * Repository contract for shelf operations.
 *
 * Provides access to user-created curated book lists. Shelves are personal
 * organization tools that enable social discovery.
 *
 * **Read model:** own shelves are mirrored into Room by the sync engine and
 * observed reactively ([observeMyShelves], [observeById]). Discovery of other
 * users' shelves is an on-demand RPC read ([discoverShelves]) — those shelves
 * are never persisted locally.
 *
 * **Write model:** mutations dispatch over RPC and return a typed [AppResult];
 * Room is updated by the firehose echo, not an optimistic local write. There is no
 * `getOrThrow` bridge — every fallible call folds an [AppResult].
 *
 * Part of the domain layer — implementations live in the data layer.
 */
interface ShelfRepository {
    /**
     * Observe shelves owned by a specific user.
     *
     * Used for the "My Shelves" section, ordered by most recently updated.
     *
     * @param userId The owner's user ID
     * @return Flow emitting the list of the user's shelves
     */
    fun observeMyShelves(userId: String): Flow<List<Shelf>>

    /**
     * Observe the caller's own shelves that currently contain [bookId], alphabetical.
     *
     * Reactive and offline — reads the local Room mirror, re-emitting when membership changes.
     * Empty when the book is on none of the caller's shelves.
     */
    fun observeShelvesContainingBook(bookId: BookId): Flow<List<Shelf>>

    /**
     * Observe a single shelf by ID.
     *
     * @param id The shelf ID
     * @return Flow emitting the shelf or null
     */
    fun observeById(id: ShelfId): Flow<Shelf?>

    /**
     * Get a shelf by ID synchronously from the local mirror.
     *
     * @param id The shelf ID
     * @return Shelf if found, null otherwise
     */
    suspend fun getById(id: ShelfId): Shelf?

    /**
     * Get a specific user's public shelves (view-other-profile surface).
     *
     * On-demand RPC read; book counts reflect only books accessible to the caller.
     * Private shelves are never returned.
     *
     * @param userId The target user's ID
     * @return [AppResult.Success] with the user's public shelves, or [AppResult.Failure] on RPC error
     */
    suspend fun getUserShelves(userId: String): AppResult<List<Shelf>>

    /**
     * Discover public shelves owned by other users.
     *
     * On-demand RPC read; book counts reflect only books accessible to the caller.
     *
     * @return [AppResult.Success] with the discovered shelves, or [AppResult.Failure] on RPC error
     */
    suspend fun discoverShelves(): AppResult<List<Shelf>>

    /**
     * Get full shelf detail including books from the server.
     *
     * Owner receives all books; a non-owner receives the access-filtered set.
     *
     * @param shelfId The shelf ID to fetch
     * @return [AppResult.Success] with the shelf detail, or [AppResult.Failure] on RPC error
     */
    suspend fun getShelfDetail(shelfId: ShelfId): AppResult<ShelfDetail>

    /**
     * Remove a book from a shelf.
     *
     * @param shelfId The shelf to remove from
     * @param bookId The book to remove
     */
    suspend fun removeBookFromShelf(
        shelfId: ShelfId,
        bookId: BookId,
    ): AppResult<Unit>

    /**
     * Add books to a shelf.
     *
     * @param shelfId The shelf to add to
     * @param bookIds The books to add
     */
    suspend fun addBooksToShelf(
        shelfId: ShelfId,
        bookIds: List<BookId>,
    ): AppResult<Unit>

    /**
     * Reorder the books in a shelf.
     *
     * [orderedBookIds] is the new full ordering of the shelf's live members.
     *
     * @param shelfId The shelf to reorder
     * @param orderedBookIds The books in their new order
     */
    suspend fun reorderBooks(
        shelfId: ShelfId,
        orderedBookIds: List<BookId>,
    ): AppResult<Unit>

    /**
     * Create a new shelf.
     *
     * @param name The shelf name
     * @param description Optional description
     * @param isPrivate Whether the shelf is visible only to the owner
     * @return [AppResult.Success] with the created shelf, or [AppResult.Failure] on RPC error
     */
    suspend fun createShelf(
        name: String,
        description: String?,
        isPrivate: Boolean,
    ): AppResult<Shelf>

    /**
     * Update an existing shelf.
     *
     * @param shelfId The shelf ID to update
     * @param name The new name
     * @param description The new description (null to clear)
     * @param isPrivate The new privacy flag
     * @return [AppResult.Success] with the updated shelf, or [AppResult.Failure] on RPC error
     */
    suspend fun updateShelf(
        shelfId: ShelfId,
        name: String,
        description: String?,
        isPrivate: Boolean,
    ): AppResult<Shelf>

    /**
     * Delete a shelf.
     *
     * @param shelfId The shelf ID to delete
     */
    suspend fun deleteShelf(shelfId: ShelfId): AppResult<Unit>
}
