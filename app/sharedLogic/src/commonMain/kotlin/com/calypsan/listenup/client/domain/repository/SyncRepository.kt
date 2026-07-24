@file:MustUseReturnValues

package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.result.AppResult
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
     * True from when a ScanStarted sync event is received until ScanCompleted.
     * UI can use this to show "Scanning your library..." instead of empty state
     * during initial library setup.
     */
    val isServerScanning: StateFlow<Boolean>
    val scanProgress: StateFlow<ScanProgressState?>

    /**
     * Whether the shell should show the initial-population ("Building your library") screen.
     *
     * Server-authoritative and derived, not a per-process latch: true while a scan is actively
     * building, or while a still-empty library has no server-recorded initial-scan completion. It
     * clears the moment the server stamps `initial_scan_completed_at` (synced into Room) or any book
     * lands — so a rescan of an already-populated library, or a fresh device joining an existing
     * library via a sync pull, never re-shows it. This is the signal the startup readiness gate reads.
     */
    val isBuildingInitialLibrary: StateFlow<Boolean>

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
     * Establishes the firehose connection and performs a delta sync to catch up
     * on changes since the last full sync. Used on app launch when
     * a prior sync already exists.
     */
    suspend fun connectRealtime()

    /**
     * Fully re-establish real-time sync — the single recovery path foreground, pull-to-refresh, and
     * the offline-banner Retry all funnel through. Unlike [connectRealtime]'s data-only pass, this
     * re-resolves the reachable server URL (following a moved server via mDNS, exactly as a relaunch
     * does), then re-opens the sync firehose **only if it has died** (no churn on a healthy
     * connection), then runs a reconcile. This closes the "reconnects only on relaunch" gap: a
     * firehose that died while the app was backgrounded is restored by a user action.
     *
     * @param forceReconcile bypass the reconcile debounce (the explicit pull-to-refresh gesture);
     *   foreground and Retry use the default debounced pass.
     */
    suspend fun recoverRealtime(forceReconcile: Boolean = false)

    /**
     * Stop real-time sync: cancel the sync firehose and catch-up loops.
     *
     * Called on logout — otherwise the engine keeps reconnecting against a now
     * unauthenticated endpoint forever. Idempotent; safe to call when already stopped.
     */
    suspend fun disconnect()

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

    /**
     * Force a standing lifecycle reconcile now (forward catch-up → digest → refresh strategies),
     * bypassing the debounce. The manual-recovery hook behind pull-to-refresh: a user who suspects
     * a Room-mirrored surface (activity feed, library, leaderboard) is stale can force every domain
     * to re-catch-up and self-heal without a restart — the Never-Stranded fallback for the live tail.
     *
     * @return Success on completion, Failure if the engine could not start for the current user.
     */
    suspend fun refresh(): AppResult<Unit>

    /**
     * True when a synced library already exists in local Room (books present) — the offline-first
     * fallback signal: a returning user can open offline even if the server is unreachable.
     */
    suspend fun hasLocalLibrary(): Boolean
}
