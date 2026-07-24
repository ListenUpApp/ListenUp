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
}
