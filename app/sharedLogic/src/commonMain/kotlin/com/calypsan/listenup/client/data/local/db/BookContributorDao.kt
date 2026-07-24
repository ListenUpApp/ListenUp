package com.calypsan.listenup.client.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.calypsan.listenup.core.BookId

@Dao
internal interface BookContributorDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(crossRef: BookContributorCrossRef)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(crossRefs: List<BookContributorCrossRef>)

    @Query("DELETE FROM book_contributors WHERE bookId = :bookId")
    suspend fun deleteContributorsForBook(bookId: BookId)

    /**
     * Get all book relationships for a contributor.
     * Used when merging contributors (to re-link books to the target contributor).
     */
    @Query("SELECT * FROM book_contributors WHERE contributorId = :contributorId")
    suspend fun getByContributorId(contributorId: String): List<BookContributorCrossRef>

    /**
     * Get a specific book-contributor relationship.
     * Used to check if a relationship already exists before creating a new one.
     */
    @Query("SELECT * FROM book_contributors WHERE bookId = :bookId AND contributorId = :contributorId AND role = :role")
    suspend fun get(
        bookId: BookId,
        contributorId: String,
        role: String,
    ): BookContributorCrossRef?

    /**
     * Delete a specific book-contributor relationship.
     * Used when merging contributors to remove the old relationships.
     */
    @Query("DELETE FROM book_contributors WHERE bookId = :bookId AND contributorId = :contributorId AND role = :role")
    suspend fun delete(
        bookId: BookId,
        contributorId: String,
        role: String,
    )

    @Query("DELETE FROM book_contributors")
    suspend fun deleteAll()
}
