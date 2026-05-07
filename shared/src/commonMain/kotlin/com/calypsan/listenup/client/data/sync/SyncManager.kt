package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.client.core.error.ErrorBus
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.core.Timestamp
import com.calypsan.listenup.client.core.getOrNull
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.PendingOperationDao
import com.calypsan.listenup.client.data.local.db.SyncDao
import com.calypsan.listenup.client.data.local.db.clearLastSyncTime
import com.calypsan.listenup.client.data.local.db.setLastSyncTime
import com.calypsan.listenup.client.data.remote.SetupApiContract
import com.calypsan.listenup.client.data.remote.UserPreferencesApiContract
import com.calypsan.listenup.client.data.sync.model.SyncStatus
import com.calypsan.listenup.client.data.sync.pull.PullSyncOrchestrator
import com.calypsan.listenup.client.data.sync.push.PushSyncOrchestrator
import com.calypsan.listenup.client.data.sync.sse.ScanCompletedInfo
import com.calypsan.listenup.client.data.sync.sse.ScanProgressState
import com.calypsan.listenup.client.data.sync.sse.SSEEventProcessor
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.LibrarySync
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * Contract for sync operations.
 *
 * Defines the public API for syncing data and observing sync status.
 * Used by data layer components and enables testing via fake implementations.
 *
 * Note: ViewModels should use [com.calypsan.listenup.client.domain.repository.SyncRepository]
 * which maps to domain-level [com.calypsan.listenup.client.domain.model.SyncState].
 */
interface SyncManagerContract {
    /**
     * Current synchronization status.
     */
    val syncState: StateFlow<SyncStatus>

    /**
     * Whether the server is currently scanning the library.
     * True from ScanStarted until ScanCompleted SSE events.
     * UI can use this to show "Scanning your library..." instead of empty state.
     */
    val isServerScanning: StateFlow<Boolean>
    val scanProgress: StateFlow<ScanProgressState?>

    /**
     * Perform full synchronization with server.
     */
    suspend fun sync(): AppResult<Unit>

    /**
     * Connect to real-time updates (SSE) and perform a delta sync.
     *
     * Used when the app launches and a prior sync exists — no need for a full sync,
     * just reconnect SSE and pull any changes since the last sync.
     *
     * On Android, SSE is managed by Activity lifecycle (onResume/onPause).
     * On Desktop, this must be called explicitly since there's no Activity lifecycle.
     */
    suspend fun connectRealtime()

    /**
     * Handle library mismatch by clearing local data and resyncing.
     *
     * Called when the user confirms they want to discard local data
     * and resync with the server's new library.
     *
     * @param newLibraryId The new library ID to sync with
     */
    suspend fun resetForNewLibrary(newLibraryId: String): AppResult<Unit>

    /**
     * Refresh all listening events and playback positions from server.
     *
     * Fetches ALL events (ignoring delta sync cursor) and rebuilds
     * playback positions. Used after importing historical data.
     */
    suspend fun refreshListeningHistory(): AppResult<Unit>

    /**
     * Force a complete resync by clearing all local data and syncing fresh.
     *
     * Used after backup restore operations where the server data has been
     * completely replaced. Disconnects SSE, clears local database,
     * then performs a full sync.
     */
    suspend fun forceFullResync(): AppResult<Unit>
}

/**
 * Orchestrates synchronization between local Room database and server.
 *
 * This is a thin orchestrator that coordinates:
 * - Pull sync via [PullSyncOrchestrator]
 * - Push sync via [PushSyncOrchestrator]
 * - SSE event processing via [SSEEventProcessor]
 * - User preferences sync
 *
 * Sync strategy:
 * 1. Pull changes from server (books, series, contributors in parallel)
 * 2. Pull user preferences (non-blocking)
 * 3. Push local changes (future)
 * 4. Update last sync timestamp
 * 5. Rebuild FTS tables for offline search
 * 6. Connect to SSE stream for real-time updates
 */
@Suppress("LongParameterList")
class SyncManager(
    private val pullOrchestrator: PullSyncOrchestrator,
    private val pushOrchestrator: PushSyncOrchestrator,
    private val sseEventProcessor: SSEEventProcessor,
    private val coordinator: SyncCoordinator,
    private val sseManager: SSEManagerContract,
    private val setupApi: SetupApiContract,
    private val userPreferencesApi: UserPreferencesApiContract,
    private val authSession: AuthSession,
    private val playbackPreferences: PlaybackPreferences,
    private val librarySync: LibrarySync,
    private val instanceRepository: InstanceRepository,
    private val serverConfig: ServerConfig,
    private val pendingOperationDao: PendingOperationDao,
    private val libraryResetHelper: LibraryResetHelperContract,
    private val syncDao: SyncDao,
    private val bookDao: BookDao,
    private val coverDownloadWorker: CoverDownloadWorker,
    private val ftsPopulator: FtsPopulatorContract,
    private val syncMutex: SyncMutex,
    private val errorBus: ErrorBus,
    private val scope: CoroutineScope,
) : SyncManagerContract {
    // Override properties can't use explicit backing fields - must use traditional pattern
    private val _syncState = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    override val syncState: StateFlow<SyncStatus> = _syncState.asStateFlow()

    // Delegate to SSEEventProcessor which tracks ScanStarted/ScanCompleted events
    override val isServerScanning: StateFlow<Boolean> = sseEventProcessor.isServerScanning
    override val scanProgress: StateFlow<ScanProgressState?> = sseEventProcessor.scanProgress

    init {
        // Route SSE messages to processor
        // Mutex ensures SSE writes don't race with push sync conflict detection
        scope.launch {
            sseManager.eventFlow.collect { message ->
                // Handle reconnection event - trigger delta sync to catch missed events
                if (message is SSEChannelMessage.Reconnected) {
                    handleReconnection(message.disconnectedAt)
                } else {
                    syncMutex.withLock {
                        sseEventProcessor.process(message)
                    }
                }
            }
        }

        // Handle user deletion events
        scope.launch {
            sseEventProcessor.userDeletedEvent.collect { deletedInfo ->
                logger.warn { "User account deleted: ${deletedInfo.userId}, reason: ${deletedInfo.reason}" }
                // Disconnect SSE first to stop any further events
                sseManager.disconnect()
                // Clear auth tokens - this will trigger AuthState.NeedsLogin
                authSession.clearAuthTokens()
            }
        }

        // Handle scan completed events - trigger delta sync to fetch newly scanned books
        scope.launch {
            sseEventProcessor.scanCompletedEvent.collect { scanInfo ->
                handleScanCompleted(scanInfo)
            }
        }
    }

    /**
     * Handle SSE reconnection by triggering a delta sync.
     *
     * When SSE disconnects and reconnects, events during the gap are lost.
     * We catch up by:
     * 1. Flushing pending local operations first (so local changes aren't lost)
     * 2. Pulling changes since the last sync checkpoint
     */
    override suspend fun connectRealtime() {
        logger.info { "Connecting real-time updates (SSE + delta sync)..." }

        // Connect SSE first so we don't miss events during delta sync
        sseManager.connect()

        // Initialize scan state from library status API
        initializeScanState()

        // Delta sync to catch anything missed while disconnected
        try {
            syncMutex.withLock {
                // Flush pending local changes first
                pushOrchestrator.flush()

                // Pull changes since last sync
                pullOrchestrator.pull { /* suppress progress updates for background sync */ }

                // Update sync timestamp
                syncDao.setLastSyncTime(Timestamp.now())
            }
            logger.info { "Real-time connection established with delta sync" }
        } catch (e: CancellationException) {
            throw e // cooperative cancellation — never swallow
        } catch (e: Exception) {
            logger.warn(e) { "Delta sync failed during connectRealtime, SSE still connected" }
        }
    }

    /**
     * Handle SSE reconnection by triggering a delta sync.
     *
     * When SSE disconnects and reconnects, events during the gap are lost.
     * We catch up by:
     * 1. Waiting for any in-progress sync to finish (never skip — queue it)
     * 2. Flushing pending local operations first (so local changes aren't lost)
     * 3. Pulling ALL entity types with updated_after=disconnectedAt
     *
     * @param disconnectedAt RFC3339 timestamp of when the SSE connection was lost
     */
    private fun handleReconnection(disconnectedAt: String) {
        logger.info { "SSE reconnected - triggering delta sync for changes since $disconnectedAt" }

        // Launch delta sync in background (non-blocking)
        // Mutex ensures delta sync writes don't race with SSE event processing
        // NOTE: We do NOT skip if already syncing — syncMutex.withLock will wait.
        // This guarantees we never drop a reconnection sync.
        scope.launch {
            try {
                syncMutex.withLock {
                    logger.debug { "Reconnection sync acquired mutex, syncing changes since $disconnectedAt" }

                    // Flush pending operations first - ensures local changes reach server
                    // before we pull potentially conflicting server changes
                    pushOrchestrator.flush()

                    // Pull changes since disconnect time for ALL entity types.
                    // PullSyncOrchestrator.pull() uses syncDao.getLastSyncTime() internally,
                    // which should be before disconnectedAt, so it catches everything.
                    pullOrchestrator.pull { /* suppress progress updates for background sync */ }

                    // Update sync timestamp
                    val now = Timestamp.now()
                    syncDao.setLastSyncTime(now)
                }

                logger.info { "Reconnection delta sync completed successfully" }
            } catch (e: CancellationException) {
                throw e // cooperative cancellation — never swallow
            } catch (e: Exception) {
                logger.warn(e) { "Reconnection delta sync failed, will retry on next reconnect" }
                // Don't update sync state - this is a background operation
            }
        }
    }

    /**
     * Handle library scan completion by triggering a delta sync.
     *
     * When a library scan completes (initial setup or rescan), the server has new/updated books
     * that weren't pushed via individual SSE events. We fetch them via delta sync.
     */
    private fun handleScanCompleted(scanInfo: ScanCompletedInfo) {
        // Launch sync check in background (non-blocking)
        // We ALWAYS check for mismatch after scan completes, regardless of reported counts.
        // The SSE event counts may be inaccurate, and we may have missed the initial sync.
        scope.launch {
            try {
                // Only sync if we're not already syncing
                if (_syncState.value is SyncStatus.Syncing) {
                    return@launch
                }

                // Check if server has books we don't have locally
                val statusResult = setupApi.getLibraryStatus()
                if (statusResult is AppResult.Failure) {
                    logger.warn { "Scan completion sync failed: could not fetch library status" }
                    return@launch
                }
                val status = (statusResult as AppResult.Success).data
                val localBookCount = bookDao.count()

                // If server has books but we don't, we need to sync
                if (status.bookCount > 0 && localBookCount == 0) {
                    logger.info { "Post-scan mismatch: server=${status.bookCount}, local=0. Forcing full sync." }

                    syncMutex.withLock {
                        // Clear lastSyncTime to force a FULL sync, not delta.
                        // Delta sync would return 0 books because all books were created
                        // before the previous sync set lastSyncTime.
                        syncDao.clearLastSyncTime()

                        pullOrchestrator.pull { /* suppress progress updates for background sync */ }

                        try {
                            ftsPopulator.rebuildAll()
                        } catch (e: CancellationException) {
                            throw e // cooperative cancellation — never swallow
                        } catch (e: Exception) {
                            logger.warn(e) { "FTS rebuild failed after scan completion sync" }
                        }

                        syncDao.setLastSyncTime(Timestamp.now())
                    }

                    val newLocalCount = bookDao.count()
                    logger.info { "Post-scan sync complete: $newLocalCount books" }
                } else if (scanInfo.booksAdded > 0 || scanInfo.booksUpdated > 0 || scanInfo.booksRemoved > 0) {
                    // SSE reported changes - do a delta sync
                    syncMutex.withLock {
                        pushOrchestrator.flush()
                        pullOrchestrator.pull { /* suppress progress updates */ }
                        syncDao.setLastSyncTime(Timestamp.now())
                    }
                }
            } catch (e: CancellationException) {
                throw e // cooperative cancellation — never swallow
            } catch (e: Exception) {
                logger.warn(e) { "Scan completion sync failed" }
            }
        }
    }

    /**
     * Perform full synchronization with server.
     */
    override suspend fun sync(): AppResult<Unit> {
        _syncState.value = SyncStatus.Syncing

        return try {
            // Phase 0: Verify library identity
            val mismatch = verifyLibraryIdentity()
            if (mismatch != null) {
                logger.warn {
                    "Library mismatch detected: expected=${mismatch.expectedLibraryId}, actual=${mismatch.actualLibraryId}"
                }
                _syncState.value = mismatch
                return Failure(LibraryMismatchException(mismatch.expectedLibraryId, mismatch.actualLibraryId))
            }

            // Phase 1: Pull changes from server
            pullOrchestrator.pull { updateSyncState(it) }

            // Phase 2: Pull user preferences (non-blocking)
            pullUserPreferences()

            // Phase 2.5: Refresh remote URL from instance API
            refreshRemoteUrl()

            // Phase 3: Push local changes
            // Mutex ensures conflict detection reads consistent data (not mid-SSE-write)
            syncMutex.withLock {
                pushOrchestrator.flush()
            }

            // Phase 4: Update last sync time
            val now = Timestamp.now()
            syncDao.setLastSyncTime(now)

            // Phase 5: Rebuild FTS tables for offline search
            try {
                ftsPopulator.rebuildAll()
            } catch (e: CancellationException) {
                throw e // cooperative cancellation — never swallow
            } catch (e: Exception) {
                logger.warn(e) { "FTS rebuild failed, offline search may be incomplete" }
            }

            // Phase 6: Connect to SSE stream for real-time updates
            sseManager.connect()

            // Phase 6.5: Process cover download queue in background
            // Queue was populated by BookPuller during Phase 1.
            // Runs in a background coroutine so it doesn't block sync completion.
            scope.launch {
                try {
                    coverDownloadWorker.recoverInterrupted()
                    coverDownloadWorker.processQueue()
                } catch (e: CancellationException) {
                    throw e // cooperative cancellation — never swallow
                } catch (e: Exception) {
                    logger.warn(e) { "Cover download worker failed, covers will retry on next launch" }
                }
            }

            // Phase 7: Initialize scan state from library status API
            // This handles the case where a scan started before SSE connected
            // (e.g., right after library setup)
            initializeScanState()

            logger.info { "Sync completed successfully" }
            _syncState.value = SyncStatus.Success(timestamp = now)
            Success(Unit)
        } catch (e: CancellationException) {
            logger.debug { "Sync was cancelled" }
            _syncState.value = SyncStatus.Idle
            throw e
        } catch (e: CancellationException) {
            throw e // cooperative cancellation — never swallow
        } catch (e: Exception) {
            errorBus.emit(SyncError.SyncFailed(debugInfo = e.message))
            logger.error(e) { "Sync failed after retries" }
            _syncState.value = SyncStatus.Error(exception = e)

            if (coordinator.isServerUnreachableError(e)) {
                logger.warn { "Server unreachable - continuing with local data" }
            }

            Failure(e)
        }
    }

    /**
     * Force a full sync by clearing the sync checkpoint and re-syncing.
     */
    suspend fun forceFullSync(): AppResult<Unit> {
        logger.info { "Forcing full sync - clearing sync checkpoint" }
        syncDao.clearLastSyncTime()
        return sync()
    }

    /**
     * Handle library mismatch by clearing local data and resyncing.
     *
     * Called when the user confirms they want to discard local data
     * and resync with the server's new library.
     *
     * @param newLibraryId The new library ID to sync with
     */
    override suspend fun resetForNewLibrary(newLibraryId: String): AppResult<Unit> {
        logger.info { "Resetting for new library: $newLibraryId" }
        _syncState.value = SyncStatus.Syncing

        try {
            // Clear all local library data and store new library ID
            libraryResetHelper.resetForNewLibrary(newLibraryId)

            // Perform fresh sync
            return sync()
        } catch (e: CancellationException) {
            throw e // cooperative cancellation — never swallow
        } catch (e: Exception) {
            logger.error(e) { "Failed to reset for new library" }
            _syncState.value = SyncStatus.Error(exception = e)
            return Failure(e)
        }
    }

    override suspend fun refreshListeningHistory(): AppResult<Unit> {
        logger.info { "Refreshing all listening history from server..." }

        return try {
            pullOrchestrator.refreshListeningHistory()
            logger.info { "Listening history refresh complete" }
            Success(Unit)
        } catch (e: CancellationException) {
            throw e // cooperative cancellation — never swallow
        } catch (e: Exception) {
            logger.error(e) { "Failed to refresh listening history" }
            Failure(e)
        }
    }

    override suspend fun forceFullResync(): AppResult<Unit> {
        logger.info { "Force full resync - clearing local data and syncing fresh" }
        _syncState.value = SyncStatus.Syncing

        return try {
            // Disconnect SSE to stop receiving events during reset
            sseManager.disconnect()

            // Clear all local library data
            // This removes books, series, contributors, chapters, playback positions,
            // pending operations, users, and sync timestamps
            libraryResetHelper.clearLibraryData(discardPendingOperations = true)

            // Perform fresh sync (will reconnect SSE at the end)
            sync()
        } catch (e: CancellationException) {
            throw e // cooperative cancellation — never swallow
        } catch (e: Exception) {
            logger.error(e) { "Failed to force full resync" }
            _syncState.value = SyncStatus.Error(exception = e)
            Failure(e)
        }
    }

    /**
     * Refresh the remote URL from the instance API.
     * This ensures the client always has the latest remote URL,
     * even if the admin updated it since last sync.
     */
    private suspend fun refreshRemoteUrl() {
        try {
            when (val result = instanceRepository.getInstance(forceRefresh = true)) {
                is Success -> {
                    val remoteUrl = result.data.remoteUrl
                    serverConfig.setRemoteUrl(remoteUrl)
                    if (remoteUrl != null) {
                        logger.debug { "Remote URL refreshed: $remoteUrl" }
                    }
                }

                is Failure -> {
                    logger.debug { "Failed to refresh remote URL: ${result.message}" }
                }
            }
        } catch (e: CancellationException) {
            throw e // cooperative cancellation — never swallow
        } catch (e: Exception) {
            logger.debug(e) { "Failed to refresh remote URL" }
        }
    }

    private suspend fun pullUserPreferences() {
        try {
            logger.debug { "Pulling user preferences from server" }
            when (val result = userPreferencesApi.getPreferences()) {
                is Success -> {
                    val prefs = result.data
                    playbackPreferences.setDefaultPlaybackSpeed(prefs.defaultPlaybackSpeed)
                    logger.info { "User preferences synced: defaultPlaybackSpeed=${prefs.defaultPlaybackSpeed}" }
                }

                is Failure -> {
                    logger.warn { "Failed to fetch user preferences: ${result.message}" }
                }
            }
        } catch (e: CancellationException) {
            throw e // cooperative cancellation — never swallow
        } catch (e: Exception) {
            logger.warn(e) { "User preferences sync failed, using cached values" }
        }
    }

    /**
     * Initialize scan state and handle missed scan completions.
     *
     * This handles the critical timing issue where:
     * 1. Library is created and scan starts
     * 2. Scan completes before client connects SSE
     * 3. Client misses all scan events
     * 4. Client has 0 books but server has books
     *
     * Solution: After initial sync, check library status:
     * - If scanning: set UI indicator (SSE events will handle completion)
     * - If not scanning but server has books we don't: fetch them now
     *
     * The server now sets isScanning synchronously (not via async event processing),
     * so API calls reliably reflect the current scan state.
     *
     * @return true if a resync was triggered, false otherwise
     */
    private suspend fun initializeScanState(): Boolean {
        try {
            val statusResult = setupApi.getLibraryStatus()
            if (statusResult is AppResult.Failure) {
                logger.warn { "Failed to check library scan status" }
                return false
            }
            val status = (statusResult as AppResult.Success).data
            val localBookCount = bookDao.count()

            // Set UI indicator based on current scan state
            sseEventProcessor.initializeScanningState(status.isScanning)

            if (status.isScanning) {
                // Scan in progress - SSE events will notify when complete
                // and trigger delta sync via handleScanCompleted()
                return false
            }

            // Server is NOT scanning - check for sync mismatch
            if (status.bookCount > 0 && localBookCount == 0) {
                // Server has books but we don't - scan completed before we could sync
                logger.info { "Sync mismatch: server=${status.bookCount}, local=0. Forcing full sync." }

                // Clear lastSyncTime to force a FULL sync, not delta.
                // Delta sync would return 0 books because all books were created
                // before the previous sync set lastSyncTime.
                syncDao.clearLastSyncTime()

                pullOrchestrator.pull { updateSyncState(it) }

                try {
                    ftsPopulator.rebuildAll()
                } catch (e: CancellationException) {
                    throw e // cooperative cancellation — never swallow
                } catch (e: Exception) {
                    logger.warn(e) { "FTS rebuild failed after resync" }
                }

                val newLocalCount = bookDao.count()
                logger.info { "Resync complete: $newLocalCount books" }
                return true
            }

            return false
        } catch (e: CancellationException) {
            throw e // cooperative cancellation — never swallow
        } catch (e: Exception) {
            logger.warn(e) { "Failed to check library scan status" }
            return false
        }
    }

    /**
     * Internal function to update sync state.
     * Called by PullSyncOrchestrator for progress updates.
     */
    internal fun updateSyncState(status: SyncStatus) {
        _syncState.value = status
    }

    /**
     * Verify that the server's library ID matches what we're synced with.
     *
     * @return LibraryMismatch status if IDs don't match, null if verification passes
     */
    private suspend fun verifyLibraryIdentity(): SyncStatus.LibraryMismatch? {
        val storedLibraryId = librarySync.getConnectedLibraryId()

        // First sync? Fetch and store the library ID, then continue
        if (storedLibraryId == null) {
            logger.info { "First sync - fetching library ID from server" }
            val instance = instanceRepository.getInstance(forceRefresh = true).getOrNull()
            if (instance != null) {
                librarySync.setConnectedLibraryId(instance.id.value)
                logger.info { "Stored library ID: ${instance.id.value}" }
            }
            return null
        }

        // Compare with server's current library ID
        val instance = instanceRepository.getInstance(forceRefresh = true).getOrNull()
        if (instance == null) {
            // Can't reach server - allow sync to proceed with cached data
            logger.warn { "Could not fetch instance for library verification, proceeding with cached data" }
            return null
        }

        val serverLibraryId = instance.id.value
        if (serverLibraryId != storedLibraryId) {
            // Library mismatch! Check if there are pending local changes
            val hasPendingChanges = pendingOperationDao.getOldestPending() != null

            return SyncStatus.LibraryMismatch(
                expectedLibraryId = storedLibraryId,
                actualLibraryId = serverLibraryId,
                hasPendingChanges = hasPendingChanges,
            )
        }

        return null
    }
}

/**
 * Exception thrown when the server's library ID doesn't match what the client was synced with.
 */
class LibraryMismatchException(
    val expectedLibraryId: String,
    val actualLibraryId: String,
) : Exception("Library mismatch: expected $expectedLibraryId, got $actualLibraryId")
