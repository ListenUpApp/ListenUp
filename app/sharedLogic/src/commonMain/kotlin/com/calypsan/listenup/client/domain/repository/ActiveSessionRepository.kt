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
     * Observe who other than [currentUserId] is listening right now — the Activity feed's live rows.
     *
     * The live half of the same cached roster [observeActiveSessions] reads, kept fresh the same way
     * while collected, and at most one row per person (the roster holds one per user). It never
     * fails: a roster that cannot be fetched is the last one cached, and an empty one is nobody, so
     * the feed beneath it is never put at risk by presence.
     *
     * @param currentUserId The viewer, whose own session is never a row.
     * @return Flow emitting the live sessions, newest-started first
     */
    fun observeListeningNow(currentUserId: String): Flow<List<ActiveSession>>
}
