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
     * Always null with the current outbox — it keeps no in-flight marker (a drain
     * wave deletes an op on success rather than flagging it "sending").
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
     * Re-arms the operation for another dispatch attempt.
     *
     * @param id The operation ID to retry
     */
    suspend fun retry(id: String)

    /**
     * Dismiss a failed operation.
     *
     * Deletes the queued operation; local optimistic state reconciles from the
     * server on the next catch-up.
     *
     * @param id The operation ID to dismiss
     */
    suspend fun dismiss(id: String)
}
