package com.calypsan.listenup.client.data.local.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for [UserStatsEntity] operations.
 *
 * Manages cached user stats for leaderboard display.
 * Used for "All Time" period rankings where we need server-calculated totals.
 */
@Dao
interface UserStatsDao {
    /**
     * Observe all cached user stats for leaderboard display.
     * Sorted by total listening time descending.
     *
     * @return Flow emitting list of all cached user stats
     */
    @Query("SELECT * FROM user_stats ORDER BY totalTimeMs DESC")
    fun observeAll(): Flow<List<UserStatsEntity>>

    /**
     * Get stats for a specific user.
     *
     * @param userId The user ID to look up
     * @return The cached stats or null if not found
     */
    @Query("SELECT * FROM user_stats WHERE userId = :userId")
    suspend fun getById(userId: String): UserStatsEntity?

    /**
     * Observe stats for a specific user.
     *
     * @param userId The user ID to observe
     * @return Flow emitting the user's stats or null
     */
    @Query("SELECT * FROM user_stats WHERE userId = :userId")
    fun observeById(userId: String): Flow<UserStatsEntity?>

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
     * Delete stats for a specific user.
     *
     * @param userId The user ID to delete
     */
    @Query("DELETE FROM user_stats WHERE userId = :userId")
    suspend fun deleteById(userId: String)

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
}
