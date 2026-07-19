package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.core.currentEpochMilliseconds
import com.calypsan.listenup.client.core.suspendRunCatching
import com.calypsan.listenup.api.dto.scanner.ScanResultSummary
import com.calypsan.listenup.api.event.ScanBookRef
import com.calypsan.listenup.api.dto.scanner.ScanPhase
import com.calypsan.listenup.api.event.ScanEvent
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.ListeningEventDao
import com.calypsan.listenup.client.data.local.db.dao.LibraryDao
import com.calypsan.listenup.api.ScannerService
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.sync.ConnectionState
import com.calypsan.listenup.client.data.sync.CoverPresenceReconciler
import com.calypsan.listenup.client.data.sync.EngineSnapshot
import com.calypsan.listenup.client.data.sync.FtsPopulatorContract
import com.calypsan.listenup.client.data.sync.SearchIndexWatermark
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val logger = KotlinLogging.logger {}

/** Backoff between scan-progress stream re-subscriptions after the stream drops or completes. */
private const val SCAN_STREAM_RESUBSCRIBE_DELAY_MS = 2_000L

/** Debounce for the live FTS refresh — coalesces a firehose burst into one reindex after it settles. */
private const val FTS_LIVE_REFRESH_DEBOUNCE_MS = 1_000L

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
internal class SyncRepositoryImpl(
    private val syncEngine: SyncEngine,
    // Re-resolve the reachable server URL (LAN-first, mDNS relocate) — wired to
    // ConnectionCoordinator.reevaluate in DI. A lambda (not the coordinator) keeps this seam
    // trivially testable and avoids pulling the whole coordinator graph into the repository.
    private val reevaluateConnection: suspend () -> Unit,
    private val syncEngineState: SyncEngineState,
    private val authSession: AuthSession,
    private val listeningEventRecorder: ListeningEventRecorder,
    private val scannerChannel: RpcChannel<ScannerService>,
    private val bookDao: BookDao,
    private val libraryDao: LibraryDao,
    private val listeningEventDao: ListeningEventDao,
    private val ftsPopulator: FtsPopulatorContract,
    private val coverPresenceReconciler: CoverPresenceReconciler,
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

    /** Guards the at-most-once launch of the live FTS refresh observer (same rationale as the scan observer). */
    private val ftsObserverMutex = Mutex()
    private var ftsObserverStarted = false

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

    override val isServerScanning: StateFlow<Boolean>
        field = MutableStateFlow(false)

    override val scanProgress: StateFlow<ScanProgressState?>
        field = MutableStateFlow<ScanProgressState?>(null)

    /**
     * Server-authoritative initial-population gate: true only while the shell should show "Building
     * your library". Derived, not latched — a live scan is building, and so is an empty library the
     * server hasn't yet stamped scan-complete. Once the server records `initial_scan_completed_at`
     * (synced into [LibraryDao.observeHasIncompleteInitialScan]) or any book lands, it clears — so a
     * rescan of a populated library, or a fresh device joining an existing one, never re-shows it.
     */
    override val isBuildingInitialLibrary: StateFlow<Boolean> =
        combine(
            isServerScanning,
            libraryDao.observeHasIncompleteInitialScan(),
            bookDao.observeIsEmpty(),
        ) { scanning, hasIncompleteInitialScan, isEmpty ->
            scanning || (hasIncompleteInitialScan && isEmpty)
        }.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = false,
        )

    override suspend fun sync(): AppResult<Unit> = startEngineForCurrentUser()

    override suspend fun connectRealtime() {
        // Every platform's app-foreground path funnels through here (MainActivity.onResume, the
        // auth-transition collector, shell entry). It routes through the unified recover seam so a
        // firehose that died while backgrounded is actually re-opened — not just reconciled over REST
        // while the dead SSE stream stays dead (the "reconnects only on relaunch" gap). The seam
        // no-ops the reconnect when the firehose is already healthy, so a normal foreground on a live
        // connection does not churn it; lifecycleReconcile stays debounced.
        recoverRealtime()
    }

    override suspend fun recoverRealtime(forceReconcile: Boolean) {
        // Not authenticated → nothing to recover (URL re-resolution / reconnect are moot).
        if (startEngineForCurrentUser() is AppResult.Failure) return
        // Re-resolve the reachable server URL (LAN-first, mDNS relocate) as a relaunch would — a
        // server that moved (DHCP) needs its new address before we re-dial. A host:port change here
        // invalidates the streaming client (ConnectionCoordinator.observeActiveUrl → invalidateAll),
        // so the engine's reconnect below re-dials the new URL.
        reevaluateConnection()
        // Re-open the firehose if it died (no-op when healthy) + reconcile.
        syncEngine.recoverRealtime(forceReconcile = forceReconcile)
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
        //
        // Driven through [refreshListeningHistoryDetached] so the catch-up runs on the app-scoped
        // [scope], not the import wizard's viewModelScope that fires this — see that function's KDoc.
        refreshListeningHistoryDetached(
            scope = scope,
            ensureStarted = ::startEngineForCurrentUser,
            recover = syncEngine::handleCursorStale,
        )

    override suspend fun forceFullResync(): AppResult<Unit> =
        when (val started = startEngineForCurrentUser()) {
            is AppResult.Success -> {
                suspendRunCatching {
                    syncEngine.forceReconcile()
                    // Full rebuild, not incremental: forceReconcile's digest repair can rewrite rows at
                    // unchanged revisions, which a revision-watermark comparison would miss.
                    refreshSearchIndex()
                }
            }

            is AppResult.Failure -> {
                started
            }
        }

    override suspend fun refresh(): AppResult<Unit> =
        when (val started = startEngineForCurrentUser()) {
            // Pull-to-refresh routes through the recover seam too, so the reflexive "swipe down" also
            // restores a dead firehose (not only a forced data pull). forceReconcile bypasses the
            // debounce for the explicit gesture.
            is AppResult.Success -> suspendRunCatching { recoverRealtime(forceReconcile = true) }

            is AppResult.Failure -> started
        }

    /**
     * Refresh the local search index after a reconcile, isolating failure — a search-index hiccup
     * must never fail or strand a sync. With a [watermark] (snapshotted before the reconcile) only
     * the rows the reconcile changed are reindexed; without one (forceFullResync's digest repair
     * can rewrite rows at unchanged revisions, defeating the watermark) the whole index rebuilds.
     */
    private suspend fun refreshSearchIndex(watermark: SearchIndexWatermark? = null) {
        try {
            if (watermark != null) ftsPopulator.refreshSince(watermark) else ftsPopulator.rebuildAll()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Search index refresh failed; search may be stale until the next sync" }
        }
    }

    /**
     * Snapshot the FTS revision watermark before a reconcile; null (→ full rebuild) if the snapshot
     * itself fails, so an index hiccup can never block the reconcile.
     */
    private suspend fun snapshotSearchWatermark(): SearchIndexWatermark? =
        try {
            ftsPopulator.snapshotWatermark()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Search watermark snapshot failed — falling back to full rebuild" }
            null
        }

    private suspend fun startEngineForCurrentUser(): AppResult<Unit> =
        suspendRunCatching {
            val userId = authSession.getUserId() ?: return@suspendRunCatching Unit
            // One-time repair: re-stamp rows written with a blank userId during a startup
            // catch-up race (authState still Initializing when insertIfAbsent ran). Idempotent.
            listeningEventDao.reassignBlankUserId(userId).let { repaired ->
                if (repaired > 0) logger.info { "Repaired $repaired listening_events rows with a blank userId" }
            }
            // Recover any orphan span BEFORE starting the engine. Recovery needs only the local DB,
            // not sync — and running it first closes the race where the user hits play (onPlay) while
            // catch-up is still paging: a late recovery would otherwise finalize the fresh LIVE span
            // at its opening heartbeat. Process-identity in the recorder makes recovery finalize only
            // spans from a PRIOR process, so this is safe even if it interleaves with a live session.
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
            syncEngine.start(userId)
            startScanProgressObserver()
            startFtsLiveRefreshObserver()
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
            // Self-heal: converge the cover-presence marker with the on-disk covers directory
            // (external deletions; upgraders whose files predate the marker column). Cheap —
            // one directory listing + one SELECT. Isolated — must not fail engine startup.
            try {
                coverPresenceReconciler.reconcile()
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Cover-presence self-heal failed; will retry on next startup" }
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
     * Launches (once) the live FTS refresh observer. The offline full-text index is refreshed at
     * scan-completion / forceFullResync / cold-start, but a book edited on another device arrives over
     * the live SSE firehose and lands in Room WITHOUT touching the index — so it is unsearchable or
     * shows stale text until a scan/resync, denting the "offline FTS is the search fallback" promise.
     * This observer follows Room: on any searchable-content write it debounce-refreshes the index from
     * the last-indexed watermark, coalescing a firehose burst into one refresh ([refreshSince] falls
     * back to a full rebuild above its delta threshold, so a large burst is bounded). On failure the
     * watermark is NOT advanced, so the same rows retry on the next change instead of being stranded.
     */
    private suspend fun startFtsLiveRefreshObserver() {
        val shouldStart =
            ftsObserverMutex.withLock {
                if (ftsObserverStarted) {
                    false
                } else {
                    ftsObserverStarted = true
                    true
                }
            }
        if (!shouldStart) return
        scope.launch {
            var lastWatermark = ftsPopulator.snapshotWatermark()
            ftsPopulator
                .observeContentChanges()
                .drop(1) // the initial replay is current state — startup rebuildIfEmpty already covered it
                .debounce(FTS_LIVE_REFRESH_DEBOUNCE_MS)
                .collect {
                    try {
                        ftsPopulator.refreshSince(lastWatermark)
                        lastWatermark = ftsPopulator.snapshotWatermark()
                    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn(e) { "Live FTS refresh failed; will retry on the next content change" }
                    }
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
                isScanning = { isServerScanning.value },
                confirmScanFinished = { isInitialScanFinishedOnServer() },
                reconcile = {
                    val watermark = snapshotSearchWatermark()
                    syncEngine.handleCursorStale()
                    refreshSearchIndex(watermark)
                },
                setScanning = { isServerScanning.value = it },
                setProgress = { scanProgress.value = it },
            )
            delay(SCAN_STREAM_RESUBSCRIBE_DELAY_MS)
        }
    }

    /** Collects one subscription to the scan-progress stream until it errors or completes. */
    private suspend fun collectScanProgressUntilStreamEnds() {
        try {
            scannerChannel
                .stream { it.observeProgress() }
                .collect { rpcEvent ->
                    val event = (rpcEvent as? RpcEvent.Data)?.value ?: return@collect
                    applyScanEvent(
                        event = event,
                        // Read the server-authoritative flag from Room at arm time only (Started/Progress);
                        // the Completed branch drives off whether THIS run armed the gate (isGateArmed),
                        // never re-reading the flag, so the independent library-Updated (firehose) and
                        // scanner-Completed (RPC) streams can't strand each other on ordering.
                        initialScanComplete = { libraryDao.initialScanCompletedAt() != null },
                        isGateArmed = { isServerScanning.value },
                        setScanning = { isServerScanning.value = it },
                        setProgress = { scanProgress.value = it },
                        // Awaited, not fire-and-forget: the initial populating gate must stay up
                        // until this reconcile actually lands the books in Room (see applyScanEvent).
                        // Parking this collector for the duration is safe — [SyncEngine.handleCursorStale]
                        // is mutex-guarded + coalescing (no concurrent catch-up spin), Completed is
                        // already consumed, and the cold RPC stream applies backpressure rather than
                        // dropping any later event. The freshly-scanned books then feed the search
                        // index so search works without an app restart.
                        reconcile = {
                            val watermark = snapshotSearchWatermark()
                            syncEngine.handleCursorStale()
                            refreshSearchIndex(watermark)
                        },
                        nowMs = { currentEpochMilliseconds() },
                        getStartedAt = { scanStartedAtMs },
                        setStartedAt = { scanStartedAtMs = it },
                        getProgress = { scanProgress.value },
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
     * The channel folds any transport fault into an [AppResult.Failure]; treat any such failure as
     * "not finished" so recovery keeps the gate up and re-subscribes rather than latching early.
     */
    private suspend fun isInitialScanFinishedOnServer(): Boolean =
        when (val result = scannerChannel.call(idempotent = true) { it.lastScanResult() }) {
            is AppResult.Success -> {
                true
            }

            is AppResult.Failure -> {
                logger.warn { "lastScanResult probe failed during scan recovery: ${result.error.code}" }
                false
            }
        }

    /** Never-stranded reset: a dropped progress stream must not leave the shell blocked. */
    private suspend fun resetScanObserver() {
        isServerScanning.value = false
        scanProgress.value = null
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
 * **Initial-population semantics.** `isServerScanning` re-armed by a scan feeds the
 * server-authoritative [SyncRepository.isBuildingInitialLibrary] gate the navigation layer reads. It
 * must reflect **only the initial population**. A later watcher-driven incremental scan emits its own
 * `Started`/`Progress` (a fresh `correlationId`) — if those re-armed the flag they would slam the
 * populating screen back over an already-usable library. So Started/Progress arm the flag only while
 * the library has never finished its initial scan: [initialScanComplete] (read from Room's
 * server-stamped `initial_scan_completed_at` at arm time) short-circuits `setScanning(true)`.
 *
 * **Completed drives off [isGateArmed], not the flag.** The library `Updated` that stamps the flag
 * (firehose) and this scanner `Completed` (RPC) are independent streams with independent ordering, so
 * reading the flag at Completed time would race. Instead Completed acts iff THIS run armed the gate
 * ([isGateArmed] — did Started/Progress set `isServerScanning`?). That is immune to when the flag lands.
 *
 * **Completed ≠ ready.** `ScanEvent.Completed` means the *server* persisted the books, not that the
 * *client's* Room reflects them — the catch-up [reconcile] still has to pull them in. So on the
 * gate-arming Completed the gate stays up (`scanning` true) across the awaited [reconcile]; only once
 * it returns (Room now holds the library) does the gate clear. Clearing on Completed would mount the
 * shell mid-import (empty grid, then a thousand covers decoding under the catch-up — the onboarding
 * OOM). [reconcile] runs on every Completed (incrementals also pull dropped deltas), but only a
 * gate-arming one drives the gate.
 */
internal suspend fun applyScanEvent(
    event: ScanEvent,
    initialScanComplete: suspend () -> Boolean,
    isGateArmed: () -> Boolean,
    setScanning: (Boolean) -> Unit,
    setProgress: (ScanProgressState?) -> Unit,
    reconcile: suspend () -> Unit,
    nowMs: () -> Long = { 0L },
    getStartedAt: () -> Long = { 0L },
    setStartedAt: (Long) -> Unit = {},
    getProgress: () -> ScanProgressState? = { null },
) {
    when (event) {
        is ScanEvent.Started -> {
            if (!initialScanComplete()) {
                setStartedAt(nowMs())
                setScanning(true)
                setProgress(null)
            }
        }

        is ScanEvent.Progress -> {
            if (!initialScanComplete()) {
                setScanning(true)
                val prior = getProgress()
                // The PERSISTING phase reports only the save counts — the rich stats (authors, hours,
                // recent-books carousel) aren't re-sent. Preserve the prior ANALYZING state and advance
                // only phase + book counts, so the bar climbs 0→100% under "Saving library" without the
                // stats panel blanking. Every other phase builds a fresh state from the event.
                val next =
                    if (event.phase == ScanPhase.PERSISTING && prior != null) {
                        prior.copy(
                            phase = event.phase.name.lowercase(),
                            books = event.booksAnalyzed,
                            booksTotal = event.booksTotal,
                        )
                    } else {
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
                            recentBooks = mergeRecentBooks(prior?.recentBooks.orEmpty(), event.recentBooks),
                            startedAtMs = getStartedAt(),
                        )
                    }
                setProgress(next)
            }
        }

        is ScanEvent.Completed -> {
            // Did THIS run arm the gate? Drive off the local flag Started/Progress set — never re-read
            // the server flag here (the firehose that stamps it and this RPC Completed are independent
            // streams; reading it now would race their ordering).
            val drivesGate = isGateArmed()
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
                // Room now holds the library — clear the gate. The server-stamped
                // initial_scan_completed_at (synced in) keeps later incrementals from re-arming it.
                setScanning(false)
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
 * so trimming from the front would yank visible tiles out and make the strip blink/swap.
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
 * ([SyncEngine.handleCursorStale] → 19 HTTP pulls → SSE disconnect/reconnect) and the search-index
 * refresh entirely. A non-skipped reconcile now reindexes incrementally
 * ([FtsPopulatorContract.refreshSince] — typically milliseconds), reserving the full rebuild
 * ([FtsPopulatorContract.rebuildAll] → ~3.3 s on a 1 150-book library) for deltas too large to
 * reindex row-by-row.
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
 * or silently re-establishes mid-scan can miss it — which would hold `isServerScanning` up forever and
 * strand the user on the "Building your library" screen.
 *
 * **Confirm-then-clear.** Only acts while the local gate is still armed ([isScanning]). It confirms the
 * scan really finished via the server's authoritative last-scan result ([confirmScanFinished]) before
 * doing anything — during the initial population that result is absent until the books are persisted,
 * so it is a precise "is the initial scan done?" signal. Only on a confirmed-finished scan does it run
 * the catch-up [reconcile] (pulling the books the missed Completed would have) and then clear the gate;
 * the server-stamped `initial_scan_completed_at` (synced in) is what keeps it clear. An unconfirmed
 * result (scan still running, or the server unreachable) leaves the gate up so the caller re-subscribes
 * — never clearing the shell mid-import, never stranding it after one.
 *
 * Extracted as a top-level function — like [applyScanEvent] — so it is testable with recording
 * lambdas, no [SyncEngine] or RPC mock required.
 */
internal suspend fun recoverFromScanStreamEnd(
    isScanning: () -> Boolean,
    confirmScanFinished: suspend () -> Boolean,
    reconcile: suspend () -> Unit,
    setScanning: (Boolean) -> Unit,
    setProgress: (ScanProgressState?) -> Unit,
) {
    if (!isScanning()) return
    if (!confirmScanFinished()) return
    reconcile()
    setProgress(null)
    setScanning(false)
}

/**
 * Ensure the engine is up via [ensureStarted], then run the post-import [recover] catch-up on
 * [scope] — the app-scoped sync scope — **not** the caller's context, and join it.
 *
 * `refreshListeningHistory` is fired from the ABS import wizard's `viewModelScope`, which is
 * cancelled the instant the wizard is dismissed — and the wizard shows "Done" before this catch-up
 * finishes draining the firehose-suppressed imported positions into Room. Running [recover] in the
 * caller's context let a dismissal cancel it mid-drain: the imported history never landed (and
 * [SyncEngine.handleCursorStale]'s SSE reconnect never ran, leaving the firehose down) until a
 * later, app-scoped sync trigger. Launched on [scope] the catch-up completes regardless of the
 * wizard's lifecycle; the `join()` keeps the awaitable contract for callers that aren't transient.
 *
 * Extracted as a top-level function — like [recoverFromScanStreamEnd] — so it is testable with
 * recording lambdas, no [SyncEngine] mock required.
 */
internal suspend fun refreshListeningHistoryDetached(
    scope: CoroutineScope,
    ensureStarted: suspend () -> AppResult<Unit>,
    recover: suspend () -> Unit,
): AppResult<Unit> =
    when (val started = ensureStarted()) {
        is AppResult.Success -> {
            scope.launch { suspendRunCatching { recover() } }.join()
            AppResult.Success(Unit)
        }

        is AppResult.Failure -> {
            started
        }
    }
