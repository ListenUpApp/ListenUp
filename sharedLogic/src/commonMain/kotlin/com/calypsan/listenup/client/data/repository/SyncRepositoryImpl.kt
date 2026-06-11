package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.core.currentEpochMilliseconds
import com.calypsan.listenup.client.core.suspendRunCatching
import com.calypsan.listenup.api.event.ScanEvent
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.remote.ScannerRpcFactory
import com.calypsan.listenup.client.data.sync.ConnectionState
import com.calypsan.listenup.client.data.sync.SyncEngine
import com.calypsan.listenup.client.data.sync.SyncEngineState
import com.calypsan.listenup.client.domain.model.ScanProgressState
import com.calypsan.listenup.client.domain.model.SyncState
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.playback.ListeningEventRecorder
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val logger = KotlinLogging.logger {}

/** Backoff between scan-progress stream re-subscriptions after the stream drops or completes. */
private const val SCAN_STREAM_RESUBSCRIBE_DELAY_MS = 2_000L

/**
 * Bridges the domain sync facade to the renovated client sync engine.
 *
 * Also hosts orphan-span recovery: on the first successful [startEngineForCurrentUser]
 * call (i.e. first authenticated user startup), [ListeningEventRecorder.recoverOrphan] is
 * invoked once to promote any leftover tentative span from a crash into a proper
 * [com.calypsan.listenup.client.data.local.db.ListeningEventEntity]. Subsequent calls
 * (sync triggers, reconnects) skip recovery — the tentative_span table is a singleton and
 * will be empty after the first successful recovery.
 */
class SyncRepositoryImpl(
    private val syncEngine: SyncEngine,
    private val syncEngineState: SyncEngineState,
    private val authSession: AuthSession,
    private val listeningEventRecorder: ListeningEventRecorder,
    private val scannerRpcFactory: ScannerRpcFactory,
    private val bookDao: BookDao,
    private val scope: CoroutineScope,
) : SyncRepository {
    /** Ensures [ListeningEventRecorder.recoverOrphan] runs at most once per process lifetime. */
    private var orphanRecovered = false

    /**
     * Guards the at-most-once launch of the scan-progress observer. [startEngineForCurrentUser] can
     * be called concurrently (sync + connectRealtime + resetForNewLibrary) on the multi-threaded
     * [scope], so the launch decision must be a single atomic check-and-set, not a plain var read.
     */
    private val scanObserverMutex = Mutex()
    private var scanObserverStarted = false

    /**
     * Latches once the initial library-population scan completes. After that the shell is "ready"
     * and later (watcher-driven incremental) scans must never re-block it via [isServerScanning].
     * Process-lived: a fresh launch starts `false`, but with no scan running nothing re-arms the
     * overlay, so a relaunched client with books already in Room shows the library immediately.
     */
    private var hasCompletedInitialScan = false

    /** Wall-clock start of the current scan, stamped on [ScanEvent.Started] and surfaced in progress. */
    private var scanStartedAtMs: Long = 0L
    override val syncState: StateFlow<SyncState> =
        syncEngineState
            .observe()
            .map { snapshot ->
                when (snapshot.connection) {
                    ConnectionState.Connecting -> {
                        SyncState.Syncing
                    }

                    is ConnectionState.Connected -> {
                        SyncState.Success(Timestamp(snapshot.lastSuccessAtMillis ?: currentEpochMilliseconds()))
                    }

                    is ConnectionState.Disconnected -> {
                        SyncState.Idle
                    }
                }
            }.stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = SyncState.Idle,
            )

    private val _isServerScanning = MutableStateFlow(false)
    override val isServerScanning: StateFlow<Boolean> = _isServerScanning.asStateFlow()

    private val _scanProgress = MutableStateFlow<ScanProgressState?>(null)
    override val scanProgress: StateFlow<ScanProgressState?> = _scanProgress.asStateFlow()

    override suspend fun sync(): AppResult<Unit> = startEngineForCurrentUser()

    override suspend fun connectRealtime() {
        startEngineForCurrentUser()
    }

    override suspend fun disconnect() {
        syncEngine.stopAndJoin()
    }

    override suspend fun resetForNewLibrary(newLibraryId: String): AppResult<Unit> = startEngineForCurrentUser()

    override suspend fun refreshListeningHistory(): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun forceFullResync(): AppResult<Unit> =
        when (val started = startEngineForCurrentUser()) {
            is AppResult.Success -> suspendRunCatching { syncEngine.forceReconcile() }
            is AppResult.Failure -> started
        }

    private suspend fun startEngineForCurrentUser(): AppResult<Unit> =
        suspendRunCatching {
            val userId = authSession.getUserId() ?: return@suspendRunCatching Unit
            syncEngine.start(userId)
            startScanProgressObserver()
            if (!orphanRecovered) {
                orphanRecovered = true
                try {
                    listeningEventRecorder.recoverOrphan()
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Orphan recovery failure is non-fatal — log and continue.
                    // The orphan will remain and may be recovered on the next startup.
                    logger.warn(e) { "Orphan span recovery failed — will retry on next startup" }
                }
            }
        }

    /**
     * Launches (once) the live scan-progress observer over [ScannerService.observeProgress].
     *
     * The live SSE tail that delivers scanned books is best-effort (the server's change bus
     * drops events under a large-scan burst), so a freshly-scanned library can finish with the
     * client still missing rows. This observer drives the [isServerScanning]/[scanProgress] UI
     * state from the scan event stream and, on [ScanEvent.Completed], forces a catch-up
     * reconcile ([SyncEngine.handleCursorStale]) so every scanned book lands in Room — no app
     * restart required. Runs on the long-lived [scope]; failures are logged and the stream is
     * left to re-establish on the next [startEngineForCurrentUser].
     */
    private suspend fun startScanProgressObserver() {
        val shouldStart =
            scanObserverMutex.withLock {
                if (scanObserverStarted) {
                    false
                } else {
                    scanObserverStarted = true
                    true
                }
            }
        if (!shouldStart) return
        scope.launch {
            try {
                observeScanProgressResiliently()
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Scan-progress observer stopped unexpectedly" }
            } finally {
                resetScanObserver()
            }
        }
    }

    /**
     * Resilient for the process lifetime. The terminal [ScanEvent.Completed] rides a `replay = 0`
     * bus (see `ScannerServiceImpl.observeProgress`), so a single subscription that drops — or
     * silently re-establishes — mid-scan can miss it, latching the populating gate forever. So we
     * re-subscribe on every termination, and on each one run the never-stranded recovery: a missed
     * Completed must not strand the user on the populating screen (see [recoverFromScanStreamEnd]).
     */
    private suspend fun observeScanProgressResiliently() {
        while (true) {
            collectScanProgressUntilStreamEnds()
            recoverFromScanStreamEnd(
                isInitialScanComplete = { hasCompletedInitialScan },
                isScanning = { _isServerScanning.value },
                confirmScanFinished = { isInitialScanFinishedOnServer() },
                reconcile = { syncEngine.handleCursorStale() },
                setScanning = { _isServerScanning.value = it },
                setProgress = { _scanProgress.value = it },
                markInitialScanComplete = { hasCompletedInitialScan = true },
            )
            delay(SCAN_STREAM_RESUBSCRIBE_DELAY_MS)
        }
    }

    /** Collects one subscription to the scan-progress stream until it errors or completes. */
    private suspend fun collectScanProgressUntilStreamEnds() {
        try {
            scannerRpcFactory
                .get()
                .observeProgress()
                .collect { rpcEvent ->
                    val event = (rpcEvent as? RpcEvent.Data)?.value ?: return@collect
                    applyScanEvent(
                        event = event,
                        isInitialScanComplete = { hasCompletedInitialScan },
                        setScanning = { _isServerScanning.value = it },
                        setProgress = { _scanProgress.value = it },
                        markInitialScanComplete = { hasCompletedInitialScan = true },
                        // Awaited, not fire-and-forget: the initial populating gate must stay up
                        // until this reconcile actually lands the books in Room (see applyScanEvent).
                        // Parking this collector for the duration is safe — [SyncEngine.handleCursorStale]
                        // is mutex-guarded + coalescing (no concurrent catch-up spin), Completed is
                        // already consumed, and the cold RPC stream applies backpressure rather than
                        // dropping any later event.
                        reconcile = { syncEngine.handleCursorStale() },
                        nowMs = { currentEpochMilliseconds() },
                        getStartedAt = { scanStartedAtMs },
                        setStartedAt = { scanStartedAtMs = it },
                    )
                }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Scan-progress stream dropped; recovering and re-subscribing" }
        }
    }

    /**
     * Probe the server's authoritative last-scan result to confirm the initial population finished.
     * The RPC proxy throws (e.g. `WebSocketException`) on transport failure; treat any such failure
     * as "not finished" so recovery keeps the gate up and re-subscribes rather than latching early.
     */
    private suspend fun isInitialScanFinishedOnServer(): Boolean =
        try {
            scannerRpcFactory.get().lastScanResult() is AppResult.Success
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "lastScanResult probe failed during scan recovery" }
            false
        }

    /** Never-stranded reset: a dropped progress stream must not leave the shell blocked. */
    private suspend fun resetScanObserver() {
        _isServerScanning.value = false
        _scanProgress.value = null
        scanObserverMutex.withLock { scanObserverStarted = false }
    }

    override suspend fun hasLocalLibrary(): Boolean = bookDao.count() > 0
}

/**
 * Pure reducer for a single [ScanEvent]: maps it to scan-UI state (via [setScanning] / [setProgress])
 * and, on [ScanEvent.Completed], invokes [reconcile] — a catch-up that pulls the books the lossy
 * live tail may have dropped during the scan burst. Extracted as a top-level function so it is
 * testable without mocking the final [SyncEngine]: tests drive it with recording lambdas.
 *
 * **Initial-population semantics.** `isServerScanning` is the app-readiness signal: the navigation
 * layer shows a full-screen populating screen while it is `true`, because a brand-new library has
 * nothing to navigate yet. That signal must therefore reflect **only the initial population**. A
 * later watcher-driven incremental scan emits its own `Started`/`Progress` (a fresh `correlationId`)
 * — if those re-armed the flag they would slam the populating screen back over an already-usable
 * library, and a missed terminal event would latch it there forever. So once the first population
 * settles ([markInitialScanComplete]), subsequent scans never drive it: [isInitialScanComplete]
 * short-circuits `setScanning(true)`.
 *
 * **Completed ≠ ready.** `ScanEvent.Completed` means the *server* persisted the books, not that the
 * *client's* Room reflects them — the catch-up [reconcile] still has to pull them in. So on the
 * initial Completed the gate stays up (`scanning` true) across the awaited [reconcile]; only once it
 * returns (Room now holds the library) does the gate clear and readiness latch. Clearing on Completed
 * would mount the shell mid-import (empty grid, then a thousand covers decoding under the catch-up —
 * the onboarding OOM). [reconcile] runs on every Completed (incrementals also pull dropped deltas),
 * but only the initial one drives the gate.
 */
internal suspend fun applyScanEvent(
    event: ScanEvent,
    isInitialScanComplete: () -> Boolean,
    setScanning: (Boolean) -> Unit,
    setProgress: (ScanProgressState?) -> Unit,
    markInitialScanComplete: () -> Unit,
    reconcile: suspend () -> Unit,
    nowMs: () -> Long = { 0L },
    getStartedAt: () -> Long = { 0L },
    setStartedAt: (Long) -> Unit = {},
) {
    when (event) {
        is ScanEvent.Started -> {
            if (!isInitialScanComplete()) {
                setStartedAt(nowMs())
                setScanning(true)
                setProgress(null)
            }
        }

        is ScanEvent.Progress -> {
            if (!isInitialScanComplete()) {
                setScanning(true)
                setProgress(
                    ScanProgressState(
                        phase = event.phase.name.lowercase(),
                        current = event.filesWalked,
                        total = event.filesWalked,
                        added = 0,
                        updated = 0,
                        removed = 0,
                        filesTotal = event.totalFiles,
                        books = event.booksAnalyzed,
                        authors = event.authorsMatched,
                        durationMs = event.totalDurationMs,
                        currentFile = event.currentFile,
                        recentBooks = event.recentBooks,
                        startedAtMs = getStartedAt(),
                    ),
                )
            }
        }

        is ScanEvent.Completed -> {
            val drivesGate = !isInitialScanComplete()
            if (drivesGate) {
                // Server done persisting; client now imports. Drop the granular walk progress so the
                // populating screen shows its indeterminate "finishing up" state while we pull books.
                setProgress(null)
            }
            logger.info { "Scan completed — reconciling to pull scanned books" }
            reconcile()
            if (drivesGate) {
                // Room now holds the library — clear the gate and latch so no later incremental can
                // re-block the shell.
                setScanning(false)
                markInitialScanComplete()
            }
        }

        is ScanEvent.Change -> {
            // No-op: the live tail plus the Completed reconcile cover applied changes.
        }
    }
}

/**
 * Never-stranded recovery for the initial-population gate when the scan-progress stream terminates
 * (drops with an error OR completes) before delivering [ScanEvent.Completed]. That terminal event
 * rides a `replay = 0` bus (see `ScannerServiceImpl.observeProgress`), so a subscription that drops
 * or silently re-establishes mid-scan can miss it — which would latch `isServerScanning` forever and
 * strand the user on the "Building your library" screen.
 *
 * **Confirm-then-clear.** Only acts while the initial gate is still up. It confirms the scan really
 * finished via the server's authoritative last-scan result ([confirmScanFinished]) before doing
 * anything — during the initial population that result is absent until the books are persisted, so
 * it is a precise "is the initial scan done?" signal. Only on a confirmed-finished scan does it run
 * the catch-up [reconcile] (pulling the books the missed Completed would have) and then clear + latch
 * the gate. An unconfirmed result (scan still running, or the server unreachable) leaves the gate up
 * so the caller re-subscribes — never latching the shell mid-import, never stranding it after one.
 *
 * Extracted as a top-level function — like [applyScanEvent] — so it is testable with recording
 * lambdas, no [SyncEngine] or RPC mock required.
 */
internal suspend fun recoverFromScanStreamEnd(
    isInitialScanComplete: () -> Boolean,
    isScanning: () -> Boolean,
    confirmScanFinished: suspend () -> Boolean,
    reconcile: suspend () -> Unit,
    setScanning: (Boolean) -> Unit,
    setProgress: (ScanProgressState?) -> Unit,
    markInitialScanComplete: () -> Unit,
) {
    if (isInitialScanComplete() || !isScanning()) return
    if (!confirmScanFinished()) return
    reconcile()
    setProgress(null)
    setScanning(false)
    markInitialScanComplete()
}
