@file:MustUseReturnValues

package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.client.domain.model.Activity
import com.calypsan.listenup.client.domain.model.ProfileRecentBook
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
     * Observe the books [userId] most recently listened to — started, finished, or had a listening
     * session on — newest first, one entry per book, at most [limit].
     *
     * Used for the "Recently listened" strip on a profile. Only books the viewer can open appear: the
     * activity mirror is access-gated, and deleted books are left out.
     */
    fun observeRecentlyListened(
        userId: String,
        limit: Int,
    ): Flow<List<ProfileRecentBook>>
}
