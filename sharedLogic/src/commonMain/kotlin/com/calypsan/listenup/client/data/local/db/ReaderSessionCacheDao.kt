package com.calypsan.listenup.client.data.local.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * DAO for [ReaderSessionCacheEntity] — summaries of OTHER readers for a book.
 *
 * The `excludingUserId` parameter filters out the current user (whose sessions live
 * in `user_reading_sessions`). Callers that don't have a user ID should pass `""`
 * as a sentinel — no real `userId` is empty, so the `!=` filter becomes a no-op.
 */
@Dao
interface ReaderSessionCacheDao {
    @Query(
        """
        SELECT * FROM reader_sessions_cache
        WHERE bookId = :bookId AND userId != :excludingUserId
        ORDER BY isCurrentlyReading DESC, updatedAt DESC
        """,
    )
    fun observeForBook(
        bookId: String,
        excludingUserId: String,
    ): Flow<List<ReaderSessionCacheEntity>>

    @Query(
        """
        SELECT * FROM reader_sessions_cache
        WHERE bookId = :bookId AND userId != :excludingUserId
        ORDER BY isCurrentlyReading DESC, updatedAt DESC
        """,
    )
    suspend fun getForBook(
        bookId: String,
        excludingUserId: String,
    ): List<ReaderSessionCacheEntity>

    @Upsert
    suspend fun upsertAll(entries: List<ReaderSessionCacheEntity>)

    @Query("DELETE FROM reader_sessions_cache WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: String)

    @Query("DELETE FROM reader_sessions_cache")
    suspend fun deleteAll()
}
