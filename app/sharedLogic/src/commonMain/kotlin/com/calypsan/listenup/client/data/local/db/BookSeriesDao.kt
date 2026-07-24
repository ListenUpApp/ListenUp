package com.calypsan.listenup.client.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.calypsan.listenup.core.BookId

@Dao
internal interface BookSeriesDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(crossRef: BookSeriesCrossRef)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(crossRefs: List<BookSeriesCrossRef>)

    @Query("DELETE FROM book_series WHERE bookId = :bookId")
    suspend fun deleteSeriesForBook(bookId: BookId)

    @Query("DELETE FROM book_series")
    suspend fun deleteAll()
}
