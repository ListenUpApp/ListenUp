package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.domain.model.ScanProgressState
import com.calypsan.listenup.client.domain.model.SyncState
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository contract for library synchronization operations.
 *
 * Abstracts the sync infrastructure behind a domain interface, allowing
 * use cases to remain pure and independent of sync implementation details.
 *
 * Implementations handle:
 * - Coordinating pull/push operations
 * - Managing sync state
 * - Handling network errors and retries
 * - Library identity verification
 *
 * Part of the domain layer - implementations live in the data layer.
 */
interface SyncRepository {
    /**
     * Observable sync state for progress monitoring.
     *
     * Emits state changes throughout the sync lifecycle:
     * Idle -> Syncing -> Progress -> Success/Error
     *
     * ViewModels observe this to show sync progress in the UI.
     */
    val syncState: StateFlow<SyncState>

    /**
     * Whether the server is currently scanning the library.
     *
     * True from when a ScanStarted SSE event is received until ScanCompleted.
     * UI can use this to show "Scanning your library..." instead of empty state
     * during initial library setup.
     */
    val isServerScanning: StateFlow<Boolean>
    val scanProgress: StateFlow<ScanProgressState?>

    /**
     * Trigger a full library sync with the server.
     *
     * Performs:
     * 1. Pull latest changes from server
     * 2. Push pending local changes
     * 3. Download new cover images
     *
     * Progress can be observed via [syncState].
     *
     * @return Success on completion, Failure on error
     */
    suspend fun sync(): AppResult<Unit>

    /**
     * Connect to real-time updates without performing a full sync.
     *
     * Establishes SSE connection and performs a delta sync to catch up
     * on changes since the last full sync. Used on app launch when
     * a prior sync already exists.
     */
    suspend fun connectRealtime()

    /**
     * Reset local data and sync with a new library.
     *
     * Used when library mismatch is detected (server was reset/changed).
     * This clears all local data and performs a fresh sync.
     *
     * WARNING: This is destructive - any unsynced local changes will be lost.
     *
     * @param newLibraryId The new library ID to sync with
     * @return Success on completion, Failure on error
     */
    suspend fun resetForNewLibrary(newLibraryId: String): AppResult<Unit>

    /**
     * Refresh all listening events and playback positions from server.
     *
     * Used after importing data (e.g., from Audiobookshelf) to fetch historical
     * events that wouldn't be included in a normal delta sync.
     *
     * This fetches ALL events from the server (ignoring the delta sync cursor)
     * and rebuilds playback positions from them.
     *
     * @return Success on completion, Failure on error
     */
    suspend fun refreshListeningHistory(): AppResult<Unit>

    /**
     * Force a complete resync by clearing all local data and syncing fresh.
     *
     * Used after backup restore operations where the server data has been
     * completely replaced. This ensures the client state matches the new
     * server state.
     *
     * WARNING: This is destructive - all local data will be cleared, including
     * any unsynced changes and downloaded content references.
     *
     * @return Success on completion, Failure on error
     */
    suspend fun forceFullResync(): AppResult<Unit>
}
