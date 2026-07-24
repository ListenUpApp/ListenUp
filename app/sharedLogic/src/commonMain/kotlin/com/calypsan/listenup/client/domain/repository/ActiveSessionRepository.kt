package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.client.domain.model.ActiveSession
import kotlinx.coroutines.flow.Flow

/**
 * Repository contract for active reading session operations.
 *
 * Provides access to other users' current listening sessions
 * for the "What Others Are Listening To" feature.
 *
 * Part of the domain layer - implementations live in the data layer.
 */
interface ActiveSessionRepository {
    /**
     * Observe active sessions from other users.
     *
     * Excludes current user's sessions and includes all necessary
     * display data (user profile, book info).
     *
     * @param currentUserId Current user ID to exclude
     * @return Flow emitting list of active sessions
     */
    fun observeActiveSessions(currentUserId: String): Flow<List<ActiveSession>>

    /**
     * Observe count of active sessions from other users.
     *
     * Used for notification badges or counts.
     *
     * @param currentUserId Current user ID to exclude
     * @return Flow emitting count of active sessions
     */
    fun observeActiveCount(currentUserId: String): Flow<Int>
}
