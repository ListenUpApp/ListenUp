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
interface ActivityDao {
    /**
     * Observe all activities ordered by creation time (newest first).
     * Used for the Activity Feed section on Discover screen.
     *
     * @return Flow emitting list of activities
     */
    @Query("SELECT * FROM activities ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ActivityEntity>>

    /**
     * Observe paginated activities with limit.
     * Used for initial feed load with pagination.
     *
     * @param limit Maximum number of activities to return
     * @return Flow emitting list of activities
     */
    @Query("SELECT * FROM activities ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ActivityEntity>>

    /**
     * Get activities older than a cursor for pagination.
     *
     * @param beforeMs Epoch milliseconds - only return activities created before this
     * @param limit Maximum number of activities to return
     * @return List of older activities
     */
    @Query("SELECT * FROM activities WHERE createdAt < :beforeMs ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getOlderThan(
        beforeMs: Long,
        limit: Int,
    ): List<ActivityEntity>

    /**
     * Get the most recent activity's timestamp for sync cursor.
     *
     * @return Epoch milliseconds of newest activity, or null if none
     */
    @Query("SELECT MAX(createdAt) FROM activities")
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
     * @param cutoffMs Epoch milliseconds - activities with createdAt before this are deleted
     * @return Number of activities deleted
     */
    @Query("DELETE FROM activities WHERE createdAt < :cutoffMs")
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
     * LEFT JOINs with user_stats to include ALL known users, even those with no activity.
     * Users with no recent activity will show 0 values.
     *
     * Uses nested CASE to filter negative durations (from bad data) to prevent overflow.
     *
     * Note: Requires user_stats to be populated first (via All-time API call).
     *
     * @param sinceMs Epoch milliseconds - only include activities since this time
     * @return Flow emitting list of user stats
     */
    @Query(
        """
        SELECT
            us.userId,
            us.displayName,
            us.avatarColor,
            us.avatarType,
            us.avatarValue,
            COALESCE(SUM(CASE WHEN a.type = 'listening_session' AND a.durationMs > 0 THEN a.durationMs ELSE 0 END), 0) as totalTimeMs,
            COUNT(DISTINCT CASE WHEN a.type = 'finished_book' THEN a.bookId END) as booksCount
        FROM user_stats us
        LEFT JOIN activities a ON a.userId = us.userId AND a.createdAt >= :sinceMs
        GROUP BY us.userId
        ORDER BY totalTimeMs DESC
    """,
    )
    fun observeLeaderboardStats(sinceMs: Long): Flow<List<UserLeaderboardStats>>

    /**
     * Observe community totals for all users.
     * Used for the community stats footer in leaderboard.
     *
     * LEFT JOINs with user_stats to count ALL known users as the community size.
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
            (SELECT COUNT(*) FROM user_stats) as activeUsers
        FROM user_stats us
        LEFT JOIN activities a ON a.userId = us.userId AND a.createdAt >= :sinceMs
    """,
    )
    fun observeCommunityStats(sinceMs: Long): Flow<CommunityStatsProjection>
}

/**
 * Projection for leaderboard user stats aggregation.
 * Maps to Room query result columns.
 */
data class UserLeaderboardStats(
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
data class CommunityStatsProjection(
    val totalTimeMs: Long,
    val totalBooks: Int,
    val activeUsers: Int,
)
