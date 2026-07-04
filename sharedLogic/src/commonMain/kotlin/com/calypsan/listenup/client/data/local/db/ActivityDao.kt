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
     * Observe the newest [limit] live activities, fully enriched at READ time by LEFT-JOINing the
     * local `public_profiles` mirror (identity) and the `books` mirror (book card: title, cover,
     * primary author). All enrichment is in SQL, so the Flow re-emits whenever `activities`,
     * `public_profiles`, `books`, `book_contributors`, or `contributors` change — a rename of the
     * user OR the book/author reflects automatically, and there is no per-row N+1. A book that is
     * absent locally (inaccessible) or tombstoned yields a null card. Activity tombstones excluded.
     */
    @Query(
        """
        SELECT a.id, a.userId, a.type, a.occurredAt, a.bookId, a.isReread, a.durationMs,
               a.milestoneValue, a.milestoneUnit, a.shelfId, a.shelfName,
               pp.displayName AS displayName, pp.avatarType AS avatarType,
               b.title AS bookTitle, b.coverBlurHash AS bookCoverPath,
               (
                   SELECT c.name FROM book_contributors bc
                   INNER JOIN contributors c ON bc.contributorId = c.id
                   WHERE bc.bookId = b.id AND bc.role = 'author' LIMIT 1
               ) AS bookAuthorName
        FROM activities a
        LEFT JOIN public_profiles pp ON pp.id = a.userId
        LEFT JOIN books b ON b.id = a.bookId AND b.deletedAt IS NULL
        WHERE a.deletedAt IS NULL
        ORDER BY a.occurredAt DESC
        LIMIT :limit
    """,
    )
    fun observeRecent(limit: Int): Flow<List<ActivityWithProfile>>

    /**
     * Page of live activities older than [beforeMs] (keyset pagination), enriched with the author's
     * `public_profiles` identity and the book card exactly as [observeRecent]. Tombstones excluded.
     */
    @Query(
        """
        SELECT a.id, a.userId, a.type, a.occurredAt, a.bookId, a.isReread, a.durationMs,
               a.milestoneValue, a.milestoneUnit, a.shelfId, a.shelfName,
               pp.displayName AS displayName, pp.avatarType AS avatarType,
               b.title AS bookTitle, b.coverBlurHash AS bookCoverPath,
               (
                   SELECT c.name FROM book_contributors bc
                   INNER JOIN contributors c ON bc.contributorId = c.id
                   WHERE bc.bookId = b.id AND bc.role = 'author' LIMIT 1
               ) AS bookAuthorName
        FROM activities a
        LEFT JOIN public_profiles pp ON pp.id = a.userId
        LEFT JOIN books b ON b.id = a.bookId AND b.deletedAt IS NULL
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

    /**
     * Advance only the revision of an existing row (append-only re-apply). Domain fields are never
     * mutated, but converging the revision when the server re-upserts an id (idempotent replay /
     * backfill) keeps the `(id, revision)` digest from permanently drifting on that client.
     */
    @Query("UPDATE activities SET revision = :revision WHERE id = :id")
    suspend fun updateRevision(
        id: String,
        revision: Long,
    )

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

    /** Ids of every live (non-tombstoned) activity — the access-gate's local-live set. */
    @Query("SELECT id FROM activities WHERE deletedAt IS NULL")
    suspend fun liveIds(): List<String>

    /**
     * Tombstone every live activity whose id is NOT in [accessibleIds] — the access-gate prune.
     * A book deletion or share revocation shrinks the server's accessible-activity set; this evicts
     * the now-inaccessible rows locally so the member no longer sees an activity for a book they
     * lost access to (and the digest stops counting a row the server no longer serves).
     */
    @Query("UPDATE activities SET deletedAt = :now WHERE deletedAt IS NULL AND id NOT IN (:accessibleIds)")
    suspend fun tombstoneNotIn(
        accessibleIds: Set<String>,
        now: Long,
    )

    /**
     * Insert or update an activity entity.
     * If an activity with the same ID exists, it will be updated.
     *
     * @param activity The activity entity to upsert
     */
    @Upsert
    suspend fun upsert(activity: ActivityEntity)

    /**
     * Count total activities (tombstones included) — the local mirror size. NOTE: hard-DELETE queries
     * were intentionally removed. `activities` is a cursored MirroredDomain; a hard delete would drop
     * a row the server still counts in the member's digest → permanent, non-converging drift.
     * Removal is soft-delete only (tombstone via [softDelete] / the access-gate prune).
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
    val bookTitle: String?,
    val bookCoverPath: String?,
    val bookAuthorName: String?,
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
