package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.client.domain.model.PendingOperation
import kotlinx.coroutines.flow.Flow

/**
 * Domain repository contract for observing pending sync operations.
 *
 * Provides the presentation layer with access to sync status information
 * without exposing data layer implementation details.
 *
 * This is separate from the internal sync repository contract to maintain
 * Clean Architecture boundaries - the domain layer defines what the UI needs.
 */
interface PendingOperationRepository {
    /**
     * Observe visible operations (excludes silent background operations).
     *
     * Used by sync indicators to show operations that users care about.
     * Listening events, playback positions, and preferences are silent.
     *
     * @return Flow of visible pending operations
     */
    fun observeVisibleOperations(): Flow<List<PendingOperation>>

    /**
     * Observe the currently in-progress operation.
     *
     * Used to display "Syncing: ..." status.
     *
     * @return Flow of the current operation, null if none in progress
     */
    fun observeInProgressOperation(): Flow<PendingOperation?>

    /**
     * Observe failed operations that need user attention.
     *
     * Used to display error states and retry/dismiss actions.
     *
     * @return Flow of failed operations
     */
    fun observeFailedOperations(): Flow<List<PendingOperation>>

    /**
     * Retry a failed operation.
     *
     * Resets the operation to pending state for another sync attempt.
     *
     * @param id The operation ID to retry
     */
    suspend fun retry(id: String)

    /**
     * Dismiss a failed operation.
     *
     * Discards local changes and marks the entity for re-sync from server.
     *
     * @param id The operation ID to dismiss
     */
    suspend fun dismiss(id: String)
}
