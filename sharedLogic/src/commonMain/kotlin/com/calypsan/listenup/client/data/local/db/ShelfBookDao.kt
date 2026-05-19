package com.calypsan.listenup.client.data.local.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/**
 * Room DAO for [ShelfBookCrossRef] operations.
 *
 * Manages the many-to-many relationship between shelves and books for
 * offline-first shelf content display.
 */
@Dao
interface ShelfBookDao {
    /**
     * Insert or update shelf-book relationships.
     * Used during sync to populate shelf contents.
     *
     * @param shelfBooks List of shelf-book relationships to upsert
     */
    @Upsert
    suspend fun upsertAll(shelfBooks: List<ShelfBookCrossRef>)

    /**
     * Insert or update a single shelf-book relationship.
     */
    @Upsert
    suspend fun upsert(shelfBook: ShelfBookCrossRef)

    /**
     * Get cover paths for a shelf's books (up to 4 for grid display).
     * Returns books in reverse order of when they were added (newest first).
     *
     * @param shelfId The shelf ID to get covers for
     * @return List of cover info for the first 4 books
     */
    @Query(
        """
        SELECT b.coverHash, b.coverBlurHash
        FROM shelf_books lb
        JOIN books b ON lb.bookId = b.id
        WHERE lb.shelfId = :shelfId
        ORDER BY lb.addedAt DESC
        LIMIT 4
    """,
    )
    suspend fun getShelfCoverInfo(shelfId: String): List<CoverInfo>

    /**
     * Get all book IDs for a shelf, ordered by when they were added (newest first).
     *
     * @param shelfId The shelf ID
     * @return List of book IDs in the shelf
     */
    @Query(
        """
        SELECT bookId 
        FROM shelf_books 
        WHERE shelfId = :shelfId 
        ORDER BY addedAt DESC
    """,
    )
    suspend fun getShelfBookIds(shelfId: String): List<String>

    /**
     * Delete all shelf-book relationships for a specific shelf.
     * Used when a shelf is deleted or when refreshing shelf contents.
     *
     * @param shelfId The shelf ID to clear relationships for
     */
    @Query("DELETE FROM shelf_books WHERE shelfId = :shelfId")
    suspend fun deleteByShelfId(shelfId: String)

    /**
     * Delete a specific shelf-book relationship.
     *
     * @param shelfId The shelf ID
     * @param bookId The book ID to remove
     */
    @Query("DELETE FROM shelf_books WHERE shelfId = :shelfId AND bookId = :bookId")
    suspend fun deleteShelfBook(
        shelfId: String,
        bookId: String,
    )

    /**
     * Update shelfId for all shelf-book entries (used when remapping temp ID to server ID).
     *
     * @param oldShelfId The old (temp) shelf ID
     * @param newShelfId The new (server) shelf ID
     */
    @Query("UPDATE shelf_books SET shelfId = :newShelfId WHERE shelfId = :oldShelfId")
    suspend fun updateShelfId(
        oldShelfId: String,
        newShelfId: String,
    )

    /**
     * Delete all shelf-book relationships.
     * Used for testing and full re-sync scenarios.
     */
    @Query("DELETE FROM shelf_books")
    suspend fun deleteAll()
}

/**
 * Data class for shelf cover information query results.
 * Used by [ShelfBookDao.getShelfCoverInfo] to return cover data.
 */
data class CoverInfo(
    val coverHash: String?,
    val coverBlurHash: String?,
)
