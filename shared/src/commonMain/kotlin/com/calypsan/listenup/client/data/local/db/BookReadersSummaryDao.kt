package com.calypsan.listenup.client.data.local.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.flow.Flow

/**
 * DAO for [BookReadersSummaryEntity] — per-book aggregate reader counts.
 *
 * Consumed only by [com.calypsan.listenup.client.data.repository.SessionRepositoryImpl].
 * Two methods: `upsert` (write during refresh) and `observeFor` (read during flow emission).
 */
@Dao
interface BookReadersSummaryDao {
    @Upsert
    suspend fun upsert(entity: BookReadersSummaryEntity)

    @Query("SELECT * FROM book_readers_summary WHERE bookId = :bookId")
    fun observeFor(bookId: BookId): Flow<BookReadersSummaryEntity?>
}
