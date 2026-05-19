package com.calypsan.listenup.client.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RewriteQueriesToDropUnusedColumns
import androidx.room.Transaction
import com.calypsan.listenup.core.BookId

@Dao
interface BookSeriesDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(crossRef: BookSeriesCrossRef)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(crossRefs: List<BookSeriesCrossRef>)

    @Query("DELETE FROM book_series WHERE bookId = :bookId")
    suspend fun deleteSeriesForBook(bookId: BookId)

    /**
     * Delete all series relationships for multiple books in a single operation.
     * Used by sync to batch-delete before re-inserting updated relationships.
     */
    @Query("DELETE FROM book_series WHERE bookId IN (:bookIds)")
    suspend fun deleteSeriesForBooks(bookIds: List<BookId>)

    @RewriteQueriesToDropUnusedColumns
    @Transaction
    @Query(
        """
        SELECT * FROM series
        INNER JOIN book_series ON series.id = book_series.seriesId
        WHERE book_series.bookId = :bookId
    """,
    )
    suspend fun getSeriesForBook(bookId: BookId): List<SeriesEntity>

    /**
     * Get all series relationships for a book, including sequence info.
     */
    @Query("SELECT * FROM book_series WHERE bookId = :bookId")
    suspend fun getBookSeriesCrossRefs(bookId: BookId): List<BookSeriesCrossRef>

    @Query("DELETE FROM book_series")
    suspend fun deleteAll()
}
