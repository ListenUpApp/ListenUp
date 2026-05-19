package com.calypsan.listenup.client.data.local.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * DAO for [UserReadingSessionEntity] — the current user's per-session history for books.
 *
 * Sessions are server-sourced today (populated by `SessionRepositoryImpl.refreshBookReaders`
 * and `ReadingSessionPuller`). W6 will introduce local-first writes.
 *
 * Queries filter by `(bookId, userId)` — the puller writes rows with the current user's ID
 * but the DAO contract supports querying by any user for future auditing needs.
 */
@Dao
interface UserReadingSessionDao {
    @Query(
        """
        SELECT * FROM user_reading_sessions
        WHERE bookId = :bookId AND userId = :userId
        ORDER BY startedAt DESC
        """,
    )
    fun observeForBook(
        bookId: String,
        userId: String,
    ): Flow<List<UserReadingSessionEntity>>

    @Query(
        """
        SELECT * FROM user_reading_sessions
        WHERE bookId = :bookId AND userId = :userId
        ORDER BY startedAt DESC
        """,
    )
    suspend fun getForBook(
        bookId: String,
        userId: String,
    ): List<UserReadingSessionEntity>

    @Upsert
    suspend fun upsertAll(sessions: List<UserReadingSessionEntity>)

    @Query("DELETE FROM user_reading_sessions WHERE bookId = :bookId AND userId = :userId")
    suspend fun deleteForBook(
        bookId: String,
        userId: String,
    )

    @Query("DELETE FROM user_reading_sessions")
    suspend fun deleteAll()
}
