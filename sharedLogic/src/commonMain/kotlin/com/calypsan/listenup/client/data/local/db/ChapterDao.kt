package com.calypsan.listenup.client.data.local.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.flow.Flow

@Dao
internal interface ChapterDao {
    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY startTime ASC")
    suspend fun getChaptersForBook(bookId: BookId): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY startTime ASC")
    fun observeChaptersForBook(bookId: BookId): Flow<List<ChapterEntity>>

    @Upsert
    suspend fun upsertAll(chapters: List<ChapterEntity>)

    @Query("DELETE FROM chapters WHERE bookId = :bookId")
    suspend fun deleteChaptersForBook(bookId: BookId)

    @Query("DELETE FROM chapters")
    suspend fun deleteAll()
}
