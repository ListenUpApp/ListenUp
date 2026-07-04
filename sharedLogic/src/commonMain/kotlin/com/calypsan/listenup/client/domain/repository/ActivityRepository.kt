@file:MustUseReturnValues

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
     * Timestamp of the newest locally-mirrored activity.
     *
     * A read-only convenience for the UI (e.g. an "up to date as of…" hint / keyset paging anchor).
     * This is NOT the sync cursor: the `activities` MirroredDomain advances its own revision cursor
     * on the sync channel, independent of this value.
     *
     * @return Epoch milliseconds of the newest live activity, or null if the mirror is empty.
     */
    suspend fun getNewestTimestamp(): Long?

    /**
     * Get the count of activities in the database.
     *
     * @return Number of activities stored locally
     */
    suspend fun count(): Int
}
