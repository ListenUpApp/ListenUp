package com.calypsan.listenup.client.data.local.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for [UserStatsEntity] operations.
 *
 * Manages per-user materialized stats, synced from the server via the stats
 * sync domain. The primary key is the user ID (`id`).
 */
@Dao
internal interface UserStatsDao {
    /**
     * Observe all cached user stats (live rows only), ordered by all-time seconds descending.
     *
     * @return Flow emitting list of all cached user stats
     */
    @Query("SELECT * FROM user_stats WHERE deletedAt IS NULL ORDER BY totalSecondsAllTime DESC")
    fun observeAll(): Flow<List<UserStatsEntity>>

    /**
     * Get stats for a specific user.
     *
     * @param userId The user ID to look up (matches the `id` column)
     * @return The cached stats or null if not found
     */
    @Query("SELECT * FROM user_stats WHERE id = :userId LIMIT 1")
    suspend fun getForUser(userId: String): UserStatsEntity?

    /**
     * Observe stats for a specific user.
     *
     * @param userId The user ID to observe (matches the `id` column)
     * @return Flow emitting the user's stats or null
     */
    @Query("SELECT * FROM user_stats WHERE id = :userId LIMIT 1")
    fun observe(userId: String): Flow<UserStatsEntity?>

    /**
     * Insert or update a user's stats.
     *
     * @param stats The stats to upsert
     */
    @Upsert
    suspend fun upsert(stats: UserStatsEntity)

    /**
     * Insert or update multiple users' stats in a single transaction.
     *
     * @param statsList List of stats to upsert
     */
    @Upsert
    suspend fun upsertAll(statsList: List<UserStatsEntity>)

    /**
     * Apply a server tombstone: set the soft-delete timestamp and revision.
     */
    @Query("UPDATE user_stats SET deletedAt = :deletedAt, revision = :revision WHERE id = :id")
    suspend fun softDelete(
        id: String,
        deletedAt: Long,
        revision: Long,
    )

    /**
     * Delete all cached stats.
     * Used for testing and full re-sync scenarios.
     */
    @Query("DELETE FROM user_stats")
    suspend fun deleteAll()

    /**
     * Count total cached user stats.
     *
     * @return Count of cached entries
     */
    @Query("SELECT COUNT(*) FROM user_stats")
    suspend fun count(): Int

    /** All rows (including tombstones) with [revision][UserStatsEntity.revision] <= [max], for digest computation. */
    @Query("SELECT id AS id, revision FROM user_stats WHERE deletedAt IS NULL AND revision <= :max")
    suspend fun digestRows(max: Long): List<IdRevision>

    /** The stored revision of the row with [id], tombstones included; null when the row has never been seen. */
    @Query("SELECT revision FROM user_stats WHERE id = :id LIMIT 1")
    suspend fun revisionOf(id: String): Long?
}
