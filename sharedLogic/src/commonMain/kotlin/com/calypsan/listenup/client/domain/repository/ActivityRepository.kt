package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.client.domain.model.Activity
import kotlinx.coroutines.flow.Flow

/**
 * Repository contract for activity feed operations.
 *
 * Provides access to social activity feed items like book starts,
 * finishes, and listening milestones.
 *
 * Part of the domain layer - implementations live in the data layer.
 */
interface ActivityRepository {
    /**
     * Observe recent activities reactively.
     *
     * Used for the Activity Feed on the Discover screen.
     *
     * @param limit Maximum number of activities to observe
     * @return Flow emitting list of activities, newest first
     */
    fun observeRecent(limit: Int): Flow<List<Activity>>

    /**
     * Get older activities for pagination.
     *
     * @param beforeMs Return activities created before this timestamp
     * @param limit Maximum number to return
     * @return List of older activities
     */
    suspend fun getOlderThan(
        beforeMs: Long,
        limit: Int,
    ): List<Activity>

    /**
     * Get the newest activity timestamp.
     *
     * Used as a cursor for sync operations.
     *
     * @return Epoch milliseconds of newest activity, or null if empty
     */
    suspend fun getNewestTimestamp(): Long?

    /**
     * Get the count of activities in the database.
     *
     * Used to check if initial fetch is needed.
     *
     * @return Number of activities stored locally
     */
    suspend fun count(): Int

    /**
     * Insert or update activities.
     *
     * Used for initial fetch and refresh from API.
     *
     * @param activities List of activities to upsert
     */
    suspend fun upsertAll(activities: List<Activity>)

    /**
     * Fetch activities from API and cache locally.
     *
     * Used for initial population and refresh operations.
     *
     * @param limit Maximum number of activities to fetch
     * @return Number of activities fetched and cached
     */
    suspend fun fetchAndCacheActivities(limit: Int): Int
}
