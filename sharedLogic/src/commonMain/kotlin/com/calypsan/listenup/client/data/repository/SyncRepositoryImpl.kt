package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.core.currentEpochMilliseconds
import com.calypsan.listenup.client.core.suspendRunCatching
import com.calypsan.listenup.api.dto.scanner.ScanResultSummary
import com.calypsan.listenup.api.event.ScanBookRef
import com.calypsan.listenup.api.event.ScanEvent
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.ListeningEventDao
import com.calypsan.listenup.client.data.remote.ScannerRpcFactory
import com.calypsan.listenup.client.data.sync.ConnectionState
import com.calypsan.listenup.client.data.sync.EngineSnapshot
import com.calypsan.listenup.client.data.sync.FtsPopulatorContract
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    private val listeningEventDao: ListeningEventDao,
    private val ftsPopulator: FtsPopulatorContract,
    private val scope: CoroutineScope,
) : SyncRepository {
    /**
     * Guards the at-most-once launch of orphan recovery. [startEngineForCurrentUser] can be called
     * concurrently (sync + connectRealtime + resetForNewLibrary) on the multi-threaded [scope], so
     * the launch decision must be a single atomic check-and-set, not a plain var read. A dedicated
     * mutex (not reused from [scanObserverMutex]) avoids cross-blocking the two independent launches.
     */
    private val orphanRecoveryMutex = Mutex()

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

    override suspend fun refreshListeningHistory(): AppResult<Unit> =
        // Import completion writes playback positions + listening events server-side under
        // `FirehoseSuppressed` (no SSE push), at revisions ABOVE the client's cursor. A digest
        // reconcile (forceFullResync → forceReconcile) compares local-vs-server digests AT that stale
        // cursor — and since both sides exclude the beyond-cursor rows, it sees no drift and pulls
        // nothing, so the progress only appeared after a restart. A forward catch-up is what drains
        // rows past the cursor; handleCursorStale runs catchUpAll + reseeds the firehose, landing the
        // imported progress live. (forceFullResync stays the right tool for a server *restore*, where
        // the whole DB diverges and the digest genuinely differs at the cursor.)
        when (val started = startEngineForCurrentUser()) {
            is AppResult.Success -> suspendRunCatching { syncEngine.handleCursorStale() }
            is AppResult.Failure -> started
        }

    override suspend fun forceFullResync(): AppResult<Unit> =
        when (val started = startEngineForCurrentUser()) {
            is AppResult.Success -> {
                suspendRunCatching {
                    syncEngine.forceReconcile()
                    // The resync pulled fresh rows; refresh the search index so search reflects them.
                    refreshSearchIndex()
                }
            }

            is AppResult.Failure -> {
                started
            }
        }

    /**
     * Rebuild the local search index, isolating failure — a search-index hiccup must never fail or
     * strand a sync. Used after a catch-up/reconcile lands fresh rows into Room.
     */
    private suspend fun refreshSearchIndex() {
        try {
            ftsPopulator.rebuildAll()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Search index rebuild failed; search may be stale until the next sync" }
        }
    }

    private suspend fun startEngineForCurrentUser(): AppResult<Unit> =
        suspendRunCatching {
            val userId = authSession.getUserId() ?: return@suspendRunCatching Unit
            // One-time repair (#532): re-stamp rows written with a blank userId during a startup
            // catch-up race (authState still Initializing when insertIfAbsent ran). Idempotent.
            listeningEventDao.reassignBlankUserId(userId).let { repaired ->
                if (repaired > 0) logger.info { "Repaired $repaired listening_events rows with a blank userId" }
            }
            syncEngine.start(userId)
            startScanProgressObserver()
            // Self-heal: an install whose library is already in Room but whose search index was
            // never populated rebuilds it here. A no-op once the index has rows, so it is safe on
            // every start. Isolated — a failure here must not fail engine startup.
            try {
                ftsPopulator.rebuildIfEmpty()
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Search index self-heal failed; will retry on next startup" }
            }
            val shouldRecover =
                orphanRecoveryMutex.withLock {
                    if (orphanRecovered) {
                        false
                    } else {
                        orphanRecovered = true
                        true
                    }
                }
            if (shouldRecover) {
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
            // Gate on a live connection. Offline, the scanner RPC stream throws "RpcClient was
            // cancelled" the instant it's subscribed (no network wait), so an unconditional re-subscribe
            // loop becomes a 2s busy-loop of stacktrace spam + wasted lastScanResult probes. Suspend here
            // until the engine is actually Connected — an offline client is then idle and silent, and the
            // loop resumes the moment the firehose reconnects.
            awaitServerConnected(syncEngineState.observe())
            collectScanProgressUntilStreamEnds()
            recoverFromScanStreamEnd(
                isInitialScanComplete = { hasCompletedInitialScan },
                isScanning = { _isServerScanning.value },
                confirmScanFinished = { isInitialScanFinishedOnServer() },
                reconcile = {
                    syncEngine.handleCursorStale()
                    refreshSearchIndex()
                },
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
                        // dropping any later event. The freshly-scanned books then feed the search
                        // index so search works without an app restart.
                        reconcile = {
                            syncEngine.handleCursorStale()
                            refreshSearchIndex()
                        },
                        nowMs = { currentEpochMilliseconds() },
                        getStartedAt = { scanStartedAtMs },
                        setStartedAt = { scanStartedAtMs = it },
                        getProgress = { _scanProgress.value },
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
 * Suspends until the sync engine reports a live [ConnectionState.Connected] firehose, returning
 * immediately if it already is. The scan-progress observer awaits this before each re-subscribe so a
 * disconnected client stays idle instead of busy-looping a dead RPC stream (see
 * [SyncRepositoryImpl.observeScanProgressResiliently]). Top-level + parameterized so the gate is unit
 * testable without standing up the whole engine.
 */
internal suspend fun awaitServerConnected(snapshots: Flow<EngineSnapshot>) {
    snapshots.first { it.connection is ConnectionState.Connected }
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
    getProgress: () -> ScanProgressState? = { null },
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
                        booksTotal = event.booksTotal,
                        authors = event.authorsMatched,
                        durationMs = event.totalDurationMs,
                        currentFile = event.currentFile,
                        // Accumulate across events rather than render the server's rolling window:
                        // the carousel grows and an already-shown title keeps its place — it never
                        // gets swapped out by a freshly-matched book.
                        recentBooks = mergeRecentBooks(getProgress()?.recentBooks.orEmpty(), event.recentBooks),
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
            if (scanResultHasChanges(event.result)) {
                logger.info {
                    "Scan completed with changes (added=${event.result.added} modified=${event.result.modified} " +
                        "removed=${event.result.removed} moved=${event.result.moved}) — reconciling"
                }
                reconcile()
            } else {
                logger.info {
                    "Scan completed with no row changes — skipping catch-up and FTS rebuild"
                }
            }
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
 * Merge a freshly-streamed [incoming] window of matched books into the already-accumulated
 * [existing] strip for the "recently matched" carousel. New titles append at the end; books already
 * present keep their position (dedupe by [ScanBookRef] value — title + author), so a tile never gets
 * its contents swapped for a different book just because that book was just scanned.
 *
 * Append-only with no cap: the marquee deliberately drifts slowly and shows the front (oldest) tiles,
 * so trimming from the front would yank visible tiles out and make the strip blink/swap (#587).
 * [ScanBookRef] is tiny (title + author) and a scan is bounded, so retaining all matched books is
 * negligible and the rendering `LazyRow` stays lazy.
 */
internal fun mergeRecentBooks(
    existing: List<ScanBookRef>,
    incoming: List<ScanBookRef>,
): List<ScanBookRef> {
    if (incoming.isEmpty()) return existing
    val seen = existing.toHashSet()
    val merged = existing.toMutableList()
    for (book in incoming) if (seen.add(book)) merged += book
    return merged
}

/**
 * Returns `true` when a [ScanResultSummary] reports that at least one row was mutated — added,
 * modified, removed, or moved. When all four counters are zero the scan was a no-op (the server
 * walked the library and found nothing new), so the client can skip the expensive catch-up
 * ([SyncEngine.handleCursorStale] → 19 HTTP pulls → SSE disconnect/reconnect) and the FTS
 * rebuild ([FtsPopulatorContract.rebuildAll] → ~3.3 s on a 1 150-book library).
 *
 * `moved` is included because a move changes the file path stored in Room even though no book is
 * added or deleted — it is a genuine row mutation that catch-up must pull.
 */
internal fun scanResultHasChanges(result: ScanResultSummary): Boolean =
    result.added > 0 || result.modified > 0 || result.removed > 0 || result.moved > 0

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
