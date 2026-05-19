package com.calypsan.listenup.client.data.local.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Data result class for active session with joined user and book details.
 *
 * Room doesn't support @Embedded with multiple foreign keys well,
 * so we use a flat data class with all needed fields.
 */
data class ActiveSessionWithDetails(
    val sessionId: String,
    val userId: String,
    val bookId: String,
    val startedAt: Long,
    val updatedAt: Long,
    // User profile fields
    val displayName: String,
    val avatarType: String,
    val avatarValue: String?,
    val avatarColor: String,
    // Book fields
    val title: String,
    val coverBlurHash: String?,
    val authorName: String?, // Primary author name (first author)
)

/**
 * Data Access Object for active reading sessions.
 *
 * Stores other users' active sessions for the "What Others Are Listening To"
 * feature on the Discover screen. Sessions are populated via SSE events
 * and joined with UserProfileEntity and BookEntity for display.
 *
 * Sessions are ephemeral and can be cleared on app start or when stale.
 */
@Dao
interface ActiveSessionDao {
    /**
     * Observe all active sessions with user and book details.
     *
     * Excludes the current user's sessions and joins with user_profiles
     * and books tables for complete display data.
     *
     * @param currentUserId The current user's ID to exclude from results
     * @return Flow of active sessions with all display data
     */
    @Query(
        """
        SELECT
            s.sessionId, s.userId, s.bookId, s.startedAt, s.updatedAt,
            u.displayName, u.avatarType, u.avatarValue, u.avatarColor,
            b.title, b.coverBlurHash,
            (
                SELECT c.name FROM book_contributors bc
                INNER JOIN contributors c ON bc.contributorId = c.id
                WHERE bc.bookId = s.bookId AND bc.role = 'author'
                LIMIT 1
            ) as authorName
        FROM active_sessions s
        INNER JOIN user_profiles u ON s.userId = u.id
        INNER JOIN books b ON s.bookId = b.id
        WHERE s.userId != :currentUserId
        ORDER BY s.startedAt DESC
        """,
    )
    fun observeActiveSessions(currentUserId: String): Flow<List<ActiveSessionWithDetails>>

    /**
     * Get all active sessions (without joins).
     * Used for debugging and staleness checks.
     */
    @Query("SELECT * FROM active_sessions ORDER BY startedAt DESC")
    suspend fun getAll(): List<ActiveSessionEntity>

    /**
     * Insert or update an active session.
     * Called when receiving session.started SSE events.
     */
    @Upsert
    suspend fun upsert(session: ActiveSessionEntity)

    /**
     * Insert or update multiple active sessions.
     * Called during initial sync to populate sessions from server.
     */
    @Upsert
    suspend fun upsertAll(sessions: List<ActiveSessionEntity>)

    /**
     * Delete a session by ID.
     * Called when receiving session.ended SSE events.
     */
    @Query("DELETE FROM active_sessions WHERE sessionId = :sessionId")
    suspend fun deleteBySessionId(sessionId: String)

    /**
     * Delete all sessions for a specific user.
     * Useful when a user goes fully offline.
     */
    @Query("DELETE FROM active_sessions WHERE userId = :userId")
    suspend fun deleteByUserId(userId: String)

    /**
     * Delete stale sessions older than the given cutoff.
     * Sessions without updates for 24+ hours are considered stale.
     *
     * @param cutoffMs Epoch milliseconds - sessions with updatedAt before this are deleted
     * @return Number of sessions deleted
     */
    @Query("DELETE FROM active_sessions WHERE updatedAt < :cutoffMs")
    suspend fun deleteStale(cutoffMs: Long): Int

    /**
     * Delete all active sessions.
     * Called on app start to ensure fresh state.
     */
    @Query("DELETE FROM active_sessions")
    suspend fun deleteAll()

    /**
     * Count active sessions (excluding current user).
     * Used for showing notification badges or counts.
     */
    @Query("SELECT COUNT(*) FROM active_sessions WHERE userId != :currentUserId")
    fun observeActiveCount(currentUserId: String): Flow<Int>
}
