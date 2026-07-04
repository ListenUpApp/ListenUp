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
     * Observe the newest [limit] live activities, each LEFT-JOINed to its author's `public_profiles`
     * mirror so identity (display name, avatar) is enriched at READ time — a later rename reflects
     * automatically because the Flow re-emits when either `activities` OR `public_profiles` changes.
     * The book card is enriched per-row in the repository from the local book mirror. Tombstones
     * (`deletedAt` set) are excluded.
     */
    @Query(
        """
        SELECT a.id, a.userId, a.type, a.occurredAt, a.bookId, a.isReread, a.durationMs,
               a.milestoneValue, a.milestoneUnit, a.shelfId, a.shelfName,
               pp.displayName AS displayName, pp.avatarType AS avatarType, pp.avatarValue AS avatarValue
        FROM activities a
        LEFT JOIN public_profiles pp ON pp.id = a.userId
        WHERE a.deletedAt IS NULL
        ORDER BY a.occurredAt DESC
        LIMIT :limit
    """,
    )
    fun observeRecent(limit: Int): Flow<List<ActivityWithProfile>>

    /**
     * Page of live activities older than [beforeMs] (keyset pagination), enriched with the author's
     * `public_profiles` identity exactly as [observeRecent]. Tombstones excluded.
     */
    @Query(
        """
        SELECT a.id, a.userId, a.type, a.occurredAt, a.bookId, a.isReread, a.durationMs,
               a.milestoneValue, a.milestoneUnit, a.shelfId, a.shelfName,
               pp.displayName AS displayName, pp.avatarType AS avatarType, pp.avatarValue AS avatarValue
        FROM activities a
        LEFT JOIN public_profiles pp ON pp.id = a.userId
        WHERE a.occurredAt < :beforeMs AND a.deletedAt IS NULL
        ORDER BY a.occurredAt DESC
        LIMIT :limit
    """,
    )
    suspend fun getOlderThan(
        beforeMs: Long,
        limit: Int,
    ): List<ActivityWithProfile>

    /**
     * Get the most recent live activity's timestamp for sync cursor.
     *
     * @return Epoch milliseconds of newest live activity, or null if none
     */
    @Query("SELECT MAX(occurredAt) FROM activities WHERE deletedAt IS NULL")
    suspend fun getNewestTimestamp(): Long?

    /** Read a single activity row (tombstone-inclusive) — the mirror's insert-if-absent probe. */
    @Query("SELECT * FROM activities WHERE id = :id")
    suspend fun getById(id: String): ActivityEntity?

    /** Soft-delete (tombstone) an activity by id — the mirror's catch-up tombstone path. */
    @Query("UPDATE activities SET deletedAt = :deletedAt, revision = :revision WHERE id = :id")
    suspend fun softDelete(
        id: String,
        deletedAt: Long,
        revision: Long,
    )

    /** All rows (including tombstones) with revision <= max, for digest computation. */
    @Query("SELECT id AS id, revision FROM activities WHERE revision <= :max")
    suspend fun digestRows(max: Long): List<IdRevision>

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
     * TODO: The `user_stats` table now holds per-user materialized
     *  stats; this query should be updated to join `user_stats` for all-time stats and
     *  `user_profiles` for profile data once the stats sync domain handler lands.
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
        LEFT JOIN activities a ON a.userId = up.id AND a.occurredAt >= :sinceMs AND a.deletedAt IS NULL
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
        LEFT JOIN activities a ON a.userId = up.id AND a.occurredAt >= :sinceMs AND a.deletedAt IS NULL
    """,
    )
    fun observeCommunityStats(sinceMs: Long): Flow<CommunityStatsProjection>
}

/**
 * A live activity row joined to its author's `public_profiles` identity — the read-time enrichment
 * projection for the feed. Profile fields are nullable (LEFT JOIN: the author's profile may not be
 * mirrored yet); the repository falls back to a placeholder and derives the avatar colour locally.
 */
internal data class ActivityWithProfile(
    val id: String,
    val userId: String,
    val type: String,
    val occurredAt: Long,
    val bookId: String?,
    val isReread: Boolean,
    val durationMs: Long,
    val milestoneValue: Int,
    val milestoneUnit: String?,
    val shelfId: String?,
    val shelfName: String?,
    val displayName: String?,
    val avatarType: String?,
    val avatarValue: String?,
)

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
