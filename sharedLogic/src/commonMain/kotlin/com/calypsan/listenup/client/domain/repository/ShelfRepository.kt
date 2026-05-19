package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.client.domain.model.Shelf
import kotlinx.coroutines.flow.Flow

/**
 * Repository contract for shelf operations.
 *
 * Provides access to user-created curated book lists.
 * Shelves are personal organization tools that enable social discovery.
 *
 * Part of the domain layer - implementations live in the data layer.
 */
interface ShelfRepository {
    /**
     * Observe shelves owned by a specific user.
     *
     * Used for "My Shelves" section, ordered by most recently updated.
     *
     * @param userId The owner's user ID
     * @return Flow emitting list of user's shelves
     */
    fun observeMyShelves(userId: String): Flow<List<Shelf>>

    /**
     * Observe shelves from other users for discovery.
     *
     * Used for social discovery, excludes current user's shelves.
     *
     * @param currentUserId The current user's ID (to exclude)
     * @return Flow emitting list of other users' shelves
     */
    fun observeDiscoverShelves(currentUserId: String): Flow<List<Shelf>>

    /**
     * Observe a single shelf by ID.
     *
     * @param id The shelf ID
     * @return Flow emitting the shelf or null
     */
    fun observeById(id: String): Flow<Shelf?>

    /**
     * Get a shelf by ID synchronously.
     *
     * @param id The shelf ID
     * @return Shelf if found, null otherwise
     */
    suspend fun getById(id: String): Shelf?

    /**
     * Count shelves from other users.
     *
     * Used to check if initial fetch is needed.
     *
     * @param currentUserId The current user's ID (to exclude)
     * @return Count of discover shelves
     */
    suspend fun countDiscoverShelves(currentUserId: String): Int

    /**
     * Fetch current user's shelves from API and cache locally.
     *
     * Used for initial population when Room is empty (e.g., fresh install
     * or after adding sync support for shelves).
     *
     * @return [AppResult.Success] when fetch and cache succeeds, [AppResult.Failure] on API error
     */
    suspend fun fetchAndCacheMyShelves(): AppResult<Unit>

    /**
     * Fetch discover shelves from API and cache locally.
     *
     * Used for initial population of discover shelves when Room is empty,
     * and for manual refresh. Fetches shelves from other users via API
     * and stores them in the local database.
     *
     * @return [AppResult.Success] containing the number of shelves fetched, [AppResult.Failure] on API error
     */
    suspend fun fetchAndCacheDiscoverShelves(): AppResult<Int>

    /**
     * Get full shelf detail including books from the server.
     *
     * Fetches shelf details via API and updates local cache.
     *
     * @param shelfId The shelf ID to fetch
     * @return [AppResult.Success] containing the shelf detail, [AppResult.Failure] on API error
     */
    suspend fun getShelfDetail(shelfId: String): AppResult<com.calypsan.listenup.client.domain.model.ShelfDetail>

    /**
     * Remove a book from a shelf.
     *
     * @param shelfId The shelf to remove from
     * @param bookId The book to remove
     */
    suspend fun removeBookFromShelf(
        shelfId: String,
        bookId: String,
    )

    /**
     * Add books to a shelf.
     *
     * @param shelfId The shelf to add to
     * @param bookIds The books to add
     */
    suspend fun addBooksToShelf(
        shelfId: String,
        bookIds: List<String>,
    )

    /**
     * Create a new shelf.
     *
     * @param name The shelf name
     * @param description Optional description
     * @return The created shelf
     */
    suspend fun createShelf(
        name: String,
        description: String?,
    ): Shelf

    /**
     * Update an existing shelf.
     *
     * @param shelfId The shelf ID to update
     * @param name The new name
     * @param description The new description (null to clear)
     * @return The updated shelf
     */
    suspend fun updateShelf(
        shelfId: String,
        name: String,
        description: String?,
    ): Shelf

    /**
     * Delete a shelf.
     *
     * @param shelfId The shelf ID to delete
     */
    suspend fun deleteShelf(shelfId: String)
}
