package com.calypsan.listenup.client.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RewriteQueriesToDropUnusedColumns
import androidx.room.Transaction
import com.calypsan.listenup.core.BookId

@Dao
interface BookContributorDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(crossRef: BookContributorCrossRef)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(crossRefs: List<BookContributorCrossRef>)

    @Query("DELETE FROM book_contributors WHERE bookId = :bookId")
    suspend fun deleteContributorsForBook(bookId: BookId)

    /**
     * Delete all contributor relationships for multiple books in a single operation.
     * Used by sync to batch-delete before re-inserting updated relationships.
     */
    @Query("DELETE FROM book_contributors WHERE bookId IN (:bookIds)")
    suspend fun deleteContributorsForBooks(bookIds: List<BookId>)

    @RewriteQueriesToDropUnusedColumns
    @Transaction
    @Query(
        """
        SELECT * FROM contributors
        INNER JOIN book_contributors ON contributors.id = book_contributors.contributorId
        WHERE book_contributors.bookId = :bookId AND book_contributors.role = :role
    """,
    )
    suspend fun getContributorsForBookByRole(
        bookId: BookId,
        role: String,
    ): List<ContributorEntity>

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

    /**
     * Delete a book-contributor cross reference.
     */
    @Query("DELETE FROM book_contributors WHERE bookId = :bookId AND contributorId = :contributorId AND role = :role")
    suspend fun deleteCrossRef(
        bookId: BookId,
        contributorId: String,
        role: String,
    )

    @Query("DELETE FROM book_contributors")
    suspend fun deleteAll()
}
