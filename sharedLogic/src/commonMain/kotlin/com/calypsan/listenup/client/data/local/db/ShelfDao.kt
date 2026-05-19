package com.calypsan.listenup.client.data.local.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for [ShelfEntity] operations.
 *
 * Provides both reactive (Flow-based) and one-shot queries for shelves.
 * Shelves are user-created curated lists of books for personal organization
 * and social discovery.
 */
@Dao
interface ShelfDao {
    /**
     * Observe shelves owned by the specified user, ordered by most recently updated.
     * Used for the "My Shelves" section on the Home screen.
     *
     * @param userId The owner's user ID
     * @return Flow emitting list of user's shelves
     */
    @Query("SELECT * FROM shelves WHERE ownerId = :userId ORDER BY updatedAt DESC")
    fun observeMyShelves(userId: String): Flow<List<ShelfEntity>>

    /**
     * Observe shelves from other users for the Discover tab, ordered by owner name and shelf name.
     * Groups shelves by user for display purposes.
     *
     * @param userId The current user's ID (to exclude their own shelves)
     * @return Flow emitting list of other users' shelves
     */
    @Query("SELECT * FROM shelves WHERE ownerId != :userId ORDER BY ownerDisplayName ASC, name ASC")
    fun observeDiscoverShelves(userId: String): Flow<List<ShelfEntity>>

    /**
     * Observe a single shelf by ID.
     *
     * @param id The shelf ID
     * @return Flow emitting the shelf or null if not found
     */
    @Query("SELECT * FROM shelves WHERE id = :id")
    fun observeById(id: String): Flow<ShelfEntity?>

    /**
     * Get a single shelf by ID synchronously.
     *
     * @param id The shelf ID
     * @return The shelf entity or null if not found
     */
    @Query("SELECT * FROM shelves WHERE id = :id")
    suspend fun getById(id: String): ShelfEntity?

    /**
     * Insert or update a shelf entity.
     * If a shelf with the same ID exists, it will be updated.
     *
     * @param shelf The shelf entity to upsert
     */
    @Upsert
    suspend fun upsert(shelf: ShelfEntity)

    /**
     * Insert or update multiple shelf entities in a single transaction.
     *
     * @param shelves List of shelf entities to upsert
     */
    @Upsert
    suspend fun upsertAll(shelves: List<ShelfEntity>)

    /**
     * Delete a shelf by ID.
     *
     * @param id The shelf ID to delete
     */
    @Query("DELETE FROM shelves WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * Delete all shelves.
     * Used for testing and full re-sync scenarios.
     */
    @Query("DELETE FROM shelves")
    suspend fun deleteAll()

    /**
     * Update sync state for a shelf.
     */
    @Query("UPDATE shelves SET syncState = :syncState WHERE id = :id")
    suspend fun updateSyncState(
        id: String,
        syncState: SyncState,
    )

    /**
     * Remap shelf ID after server creation and update sync state.
     * Used when server assigns a new ID to a client-generated shelf.
     */
    @Query("UPDATE shelves SET id = :newId, syncState = :syncState WHERE id = :oldId")
    suspend fun updateIdAndSyncState(
        oldId: String,
        newId: String,
        syncState: SyncState,
    )

    /**
     * Count shelves from other users.
     * Used to check if initial fetch is needed.
     *
     * @param userId The current user's ID (to exclude their own shelves)
     * @return Count of other users' shelves
     */
    @Query("SELECT COUNT(*) FROM shelves WHERE ownerId != :userId")
    suspend fun countDiscoverShelves(userId: String): Int

    /**
     * Get cover paths for a shelf using the join table.
     * Returns up to 4 cover paths for the shelf card grid display.
     *
     * @param shelfId The shelf ID
     * @return List of cover URLs for the first 4 books in the shelf
     */
    @Query(
        """
        SELECT b.coverHash
        FROM shelf_books lb
        JOIN books b ON lb.bookId = b.id
        WHERE lb.shelfId = :shelfId AND b.coverHash IS NOT NULL
        ORDER BY lb.addedAt DESC
        LIMIT 4
    """,
    )
    suspend fun getShelfCoverPaths(shelfId: String): List<String>
}
