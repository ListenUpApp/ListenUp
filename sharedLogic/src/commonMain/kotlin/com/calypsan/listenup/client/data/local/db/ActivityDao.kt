package com.calypsan.listenup.client.data.local.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for [ActivityEntity] operations.
 *
 * Provides reactive (Flow-based) and one-shot queries for activity feed.
 * Activities are stored locally for offline-first display.
 */
@Dao
internal interface ActivityDao {
    /**
     * Observe all activities ordered by occurrence time (newest first).
     * Used for the Activity Feed section on Discover screen.
     *
     * @return Flow emitting list of activities
     */
    @Query("SELECT * FROM activities ORDER BY occurredAt DESC")
    fun observeAll(): Flow<List<ActivityEntity>>

    /**
     * Observe paginated activities with limit.
     * Used for initial feed load with pagination.
     *
     * @param limit Maximum number of activities to return
     * @return Flow emitting list of activities
     */
    @Query("SELECT * FROM activities ORDER BY occurredAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ActivityEntity>>

    /**
     * Get activities older than a cursor for pagination.
     *
     * @param beforeMs Epoch milliseconds - only return activities that occurred before this
     * @param limit Maximum number of activities to return
     * @return List of older activities
     */
    @Query("SELECT * FROM activities WHERE occurredAt < :beforeMs ORDER BY occurredAt DESC LIMIT :limit")
    suspend fun getOlderThan(
        beforeMs: Long,
        limit: Int,
    ): List<ActivityEntity>

    /**
     * Get the most recent activity's timestamp for sync cursor.
     *
     * @return Epoch milliseconds of newest activity, or null if none
     */
    @Query("SELECT MAX(occurredAt) FROM activities")
    suspend fun getNewestTimestamp(): Long?

    /**
     * Insert or update an activity entity.
     * If an activity with the same ID exists, it will be updated.
     *
     * @param activity The activity entity to upsert
     */
    @Upsert
    suspend fun upsert(activity: ActivityEntity)

    /**
     * Insert or update multiple activity entities in a single transaction.
     *
     * @param activities List of activity entities to upsert
     */
    @Upsert
    suspend fun upsertAll(activities: List<ActivityEntity>)

    /**
     * Delete an activity by ID.
     *
     * @param id The activity ID to delete
     */
    @Query("DELETE FROM activities WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * Delete activities older than the given cutoff.
     * Used to prune old activities (> 30 days) to save storage.
     *
     * @param cutoffMs Epoch milliseconds - activities with occurredAt before this are deleted
     * @return Number of activities deleted
     */
    @Query("DELETE FROM activities WHERE occurredAt < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int

    /**
     * Delete all activities.
     * Used for testing and full re-sync scenarios.
     */
    @Query("DELETE FROM activities")
    suspend fun deleteAll()

    /**
     * Count total activities.
     * Used for debugging and monitoring.
     */
    @Query("SELECT COUNT(*) FROM activities")
    suspend fun count(): Int

    // ==================== Leaderboard Aggregation Queries ====================

    /**
     * Observe aggregated stats for all users (for leaderboard).
     *
     * Joins `user_profiles` (for display data) with `activities` (for recent stats).
     * Users with no recent activity show 0 values.
     *
     * TODO(P2-leaderboard): The P2 `user_stats` table now holds per-user materialized
     *  stats; this query should be updated to join `user_stats` for all-time stats and
     *  `user_profiles` for profile data once the P2 sync domain handler lands.
     *
     * @param sinceMs Epoch milliseconds - only include activities since this time
     * @return Flow emitting list of user stats
     */
    @Query(
        """
        SELECT
            up.id as userId,
            up.displayName,
            up.avatarColor,
            up.avatarType,
            up.avatarValue,
            COALESCE(SUM(CASE WHEN a.type = 'listening_session' AND a.durationMs > 0 THEN a.durationMs ELSE 0 END), 0) as totalTimeMs,
            COUNT(DISTINCT CASE WHEN a.type = 'finished_book' THEN a.bookId END) as booksCount
        FROM user_profiles up
        LEFT JOIN activities a ON a.userId = up.id AND a.occurredAt >= :sinceMs
        GROUP BY up.id
        ORDER BY totalTimeMs DESC
    """,
    )
    fun observeLeaderboardStats(sinceMs: Long): Flow<List<UserLeaderboardStats>>

    /**
     * Observe community totals for all users.
     * Used for the community stats footer in leaderboard.
     *
     * Uses `user_profiles` as the universe of known users.
     * Uses durationMs > 0 check to filter negative durations (from bad data) to prevent overflow.
     *
     * @param sinceMs Epoch milliseconds - only include activities since this time
     * @return Flow emitting community aggregate stats
     */
    @Query(
        """
        SELECT
            COALESCE(SUM(CASE WHEN a.type = 'listening_session' AND a.durationMs > 0 THEN a.durationMs ELSE 0 END), 0) as totalTimeMs,
            COUNT(DISTINCT CASE WHEN a.type = 'finished_book' THEN a.bookId END) as totalBooks,
            (SELECT COUNT(*) FROM user_profiles) as activeUsers
        FROM user_profiles up
        LEFT JOIN activities a ON a.userId = up.id AND a.occurredAt >= :sinceMs
    """,
    )
    fun observeCommunityStats(sinceMs: Long): Flow<CommunityStatsProjection>
}

/**
 * Projection for leaderboard user stats aggregation.
 * Maps to Room query result columns.
 */
internal data class UserLeaderboardStats(
    val userId: String,
    val displayName: String,
    val avatarColor: String,
    val avatarType: String,
    val avatarValue: String?,
    val totalTimeMs: Long,
    val booksCount: Int,
)

/**
 * Projection for community aggregate stats.
 * Maps to Room query result columns.
 */
internal data class CommunityStatsProjection(
    val totalTimeMs: Long,
    val totalBooks: Int,
    val activeUsers: Int,
)
