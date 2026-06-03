package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.currentEpochMilliseconds
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val logger = KotlinLogging.logger {}

/**
 * Lifecycle composer for the client sync engine.
 *
 * Started on user sign-in (or app launch when already signed in). Internal order:
 *
 *   1. Verify queued-op ownership; clear if signed-in user differs.
 *   2. Run REST catch-up to completion across every registered domain.
 *   3. Seed [SseClient]'s `lastEventId` from the highest cursor.
 *   4. Connect SSE.
 *
 * Catch-up and SSE never overlap — the contract requires catch-up to fully
 * drain before live tail begins, otherwise events arrive out of order.
 *
 * On sign-out the SSE connection closes; the queue is paused (not cleared).
 *
 * The engine owns the frame collector so every connection has exactly one path
 * from [SseClient.frames] into [SyncEventDispatcher].
 */
class SyncEngine(
    private val registry: ClientSyncDomainRegistry,
    private val queue: PendingOperationQueue,
    private val state: SyncEngineState,
    private val store: SyncCursorStore,
    private val catchUp: CatchUp,
    private val sseClient: SseClient,
    private val reconciler: SyncReconciler,
    private val dispatcher: SyncEventDispatcher,
    private val downloadRepository: DownloadRepository,
    private val scope: CoroutineScope,
    private val retryBackoffMillis: Long = DEFAULT_RETRY_BACKOFF_MILLIS,
) {
    private var frameCollectorJob: Job? = null
    private var connectionUpDrainJob: Job? = null
    private var enqueueDrainJob: Job? = null
    private var queueDepthJob: Job? = null
    private var failureCountJob: Job? = null

    // Serializes drain waves so concurrent triggers (connection-up + enqueue +
    // retry) coalesce into one wave at a time. drain() reads from the DAO and
    // mutates rows; without a Mutex two concurrent drains would dispatch the
    // same op twice. The Mutex is per-engine, not per-op — per-op FIFO is the
    // queue's SQL filter responsibility.
    private val drainMutex = Mutex()

    // Serializes the start/stop handshake so concurrent re-entries (e.g.
    // MainActivity.onResume firing twice in quick succession → two
    // syncRepository.connectRealtime() → two engine.start()) can't race two
    // catch-up loops or two SSE connect()s. The mutex protects only the
    // bookkeeping (currentUser + engineJob assignment); the engineJob itself
    // runs the long-lived startup body outside the mutex so a different-user
    // start can cancel an in-flight startup without holding the lock across
    // catch-up.
    private val startMutex = Mutex()
    private var currentUser: String? = null
    private var engineJob: Job? = null

    // Serializes + coalesces CursorStale recovery. During initial population the server can emit
    // CursorStale repeatedly (its replay-bus floor outruns the client's reseeded cursor while the
    // scan is still writing), and the dispatcher routes each straight into handleCursorStale — on
    // top of the scan-completion reconcile. Unguarded, every signal started ANOTHER full catchUpAll
    // concurrently: overlapping page re-decodes (runaway large-object GC) that saturated the
    // dispatcher and starved the UI. This guard runs at most one recovery at a time and collapses a
    // burst of triggers into a single follow-up pass.
    private val cursorStaleMutex = Mutex()
    private var cursorStaleRunning = false
    private var cursorStalePending = false

    // Flips to true on the last line of [runStart] for the active user. If
    // [runStart] throws before that line, the flag stays false even though
    // engineJob has completed — that's the signal start() uses to retry
    // instead of silently no-op'ing on a failed prior attempt. Reset to
    // false at the top of every fresh start (different user OR retry).
    private var currentUserStarted: Boolean = false

    /**
     * Start the engine for [currentUserId]. Idempotent under re-entry:
     *
     *  - Concurrent or sequential calls for the *same* user share a single
     *    startup; the second caller observes "already starting/started" and
     *    returns without re-running catch-up or re-connecting SSE — provided
     *    the prior attempt is either still in flight (`engineJob.isActive`)
     *    or has completed successfully (`currentUserStarted`). If it threw
     *    before reaching the end of [runStart] the call retries the startup.
     *  - A call for a *different* user cancels the prior engine job, which
     *    propagates `CancellationException` through whatever step of
     *    [runStart] is in flight (catch-up, suspended `ready.await()` inside
     *    `ensure*` setup, `sseClient.connect()`). It does NOT tear down the
     *    long-running collectors — `frameCollectorJob`, `connectionUpDrainJob`,
     *    `enqueueDrainJob`, `queueDepthJob`, `failureCountJob` are launched
     *    into [scope], not as children of `engineJob`, so they survive the
     *    cancellation. That's intentional: they're user-agnostic (the queue
     *    is wiped on user change via `clearForUserChange`, dispatcher and
     *    sseClient are singletons), so leaving them in place avoids a
     *    re-subscription stampede on every user switch. Hard shutdown that
     *    must tear them down (tests, sign-out flows) goes through
     *    [stopAndJoin], which cancels each collector job explicitly.
     *
     * The caller is still suspended until the launched startup completes (or
     * is cancelled) — the existing contract that `start()` returns *after*
     * catch-up has run and SSE is connected is preserved. External
     * cancellability comes from the engine job being a child of [scope].
     */
    suspend fun start(currentUserId: String) {
        val job =
            startMutex.withLock {
                if (currentUser == currentUserId &&
                    (engineJob?.isActive == true || currentUserStarted)
                ) {
                    // Same user AND the prior startup is either still in flight
                    // (engineJob.isActive) or has completed successfully
                    // (currentUserStarted). Nothing for this caller to do.
                    //
                    // The currentUserStarted check is load-bearing: if the prior
                    // runStart() threw (DB error in clearForUserChange, IO error in
                    // sseClient.connect(), unexpected exception in any step), the
                    // launched job completed exceptionally — isActive is false AND
                    // currentUserStarted is false (because the flag-flip is the
                    // last line of runStart, which never ran). currentUser is still
                    // set, so without the currentUserStarted clause every subsequent
                    // start() would silently no-op forever — the recovery path
                    // SyncRepositoryImpl.forceFullResync() (which calls engine.start())
                    // would be dead. With the clause, a failed start is retryable.
                    logger.debug { "start($currentUserId) — already active for this user; no-op" }
                    return
                }
                // Different user (or first start, or retry-after-failure): cancel
                // any prior engine job so its in-flight catch-up / SSE work (or
                // its already-completed exceptional state) tears down before we
                // claim ownership.
                engineJob?.cancelAndJoin()
                currentUser = currentUserId
                currentUserStarted = false
                scope.launch { runStart(currentUserId) }.also { engineJob = it }
            }
        // Wait for the launched startup to complete (or be cancelled by a
        // subsequent different-user start). `join()` returns normally on
        // cancellation, so the caller never observes a `CancellationException`
        // from a different-user pre-emption — that's the new user's concern.
        job.join()
    }

    /**
     * The actual startup sequence, run inside the engine job so it's
     * cancellable as a unit. Order matters and is documented in the class
     * KDoc: queue ownership → catch-up → seed SSE cursor → collectors →
     * drain scheduling → state observers → SSE connect.
     */
    private suspend fun runStart(currentUserId: String) {
        // Step 1: queue ownership.
        queue.clearForUserChange(currentUserId)
        // Step 2: catch-up across all registered domains.
        catchUp.catchUpAll(registry)
        // Step 3: seed SSE resume cursor.
        sseClient.seedLastEventId(store.highestCursor())
        // Step 4: collect frames before connecting so immediate frames are not dropped.
        ensureFrameCollector()
        // Step 5: schedule pending-queue drains before connecting so the
        // connection-up transition is observed and any already-queued ops
        // drain promptly. Suspend until both collectors are actively subscribed
        // — otherwise an enqueue racing the launch would be lost (StateFlow's
        // replayed value at subscribe time gets dropped, and there's nothing
        // after it).
        ensureDrainScheduling()
        // Step 6: forward queue-depth and failure-count observers into
        // SyncEngineState so the diagnostics surface reflects reality. Without
        // this wire-up `pendingQueueDepth` / `pendingFailureCount` stay stuck
        // at 0 forever even though the queue is doing work.
        ensureStateObservers()
        // Step 7: connect SSE.
        sseClient.connect()
        // Step 8: digest reconciliation — compare local domain digests against the
        // server's and re-pull any domain that has drifted. Runs after SSE connect
        // so the live tail is already in place; reconcileAll() is non-throwing.
        reconciler.reconcileAll()
        // All steps succeeded — flag the user's setup complete so a subsequent
        // start() for the same user is a no-op. If any step above threw, this
        // line is never reached, the flag stays false, and start() retries.
        currentUserStarted = true
    }

    fun stop() {
        // stop() is non-suspend so it can't take the startMutex. That means
        // the engineJob/currentUser writes below can race a concurrent start()
        // and lose: if start() is inside withLock between assigning currentUser
        // and assigning engineJob when stop() runs, stop's nulls land first and
        // start's writes overwrite them — net result is engine continues running
        // as if stop() never happened. This is acceptable because:
        //
        //   1. The dominant caller is MainActivity.onPause() racing onResume() →
        //      start(). Android's lifecycle FSM serializes pause/resume per
        //      Activity, so the race is structurally impossible at the call site.
        //   2. Any caller that needs hard-shutdown semantics uses [stopAndJoin],
        //      which takes startMutex and join()s every collector.
        //
        // If a non-Android caller is added later that depends on stop()'s race
        // semantics, promote this to `suspend` + `startMutex.withLock { ... }`.
        //
        // Note: cancelling engineJob propagates CancellationException through
        // an in-flight runStart() (catch-up, ensure* setup, sseClient.connect),
        // but does NOT cancel the long-running collectors below — they're
        // launched into [scope], not as children of engineJob. We cancel them
        // explicitly here.
        engineJob?.cancel()
        engineJob = null
        currentUser = null
        currentUserStarted = false
        frameCollectorJob?.cancel()
        frameCollectorJob = null
        connectionUpDrainJob?.cancel()
        connectionUpDrainJob = null
        enqueueDrainJob?.cancel()
        enqueueDrainJob = null
        queueDepthJob?.cancel()
        queueDepthJob = null
        failureCountJob?.cancel()
        failureCountJob = null
        sseClient.disconnect()
    }

    /**
     * Recover from a server-issued `SyncControl.CursorStale`. The dispatcher
     * invokes this when the SSE firehose announces that the client's
     * `Last-Event-Id` precedes the bus's live-tail replay floor.
     *
     * The recovery sequence is intentionally explicit at this layer so the
     * ordering is auditable in one place:
     *  1. Disconnect SSE so the catch-up REST pass cannot interleave with
     *     a live-tail frame whose revision the cursor store hasn't recorded yet.
     *  2. Run catch-up across every registered domain. Each domain's catch-up
     *     advances [store] as it drains; per-domain failures are logged but do
     *     not abort the loop — one slow domain shouldn't strand the rest.
     *  3. Reseed the SSE client's `lastEventId` from the new high-water cursor.
     *     Without this, the reconnect would re-issue the request with the
     *     stale `Last-Event-Id`, the server would emit `CursorStale` again,
     *     and the loop would spin forever (the H1 bug).
     *  4. Reconnect SSE. Live tail resumes from the new cursor.
     */
    internal suspend fun handleCursorStale() {
        // Coalesce: if a recovery is already running, request a single follow-up pass and return
        // instead of starting a concurrent catchUpAll. The first caller drains the pending flag in a
        // loop so the last-requested signal is honoured exactly once.
        val shouldRun =
            cursorStaleMutex.withLock {
                if (cursorStaleRunning) {
                    cursorStalePending = true
                    false
                } else {
                    cursorStaleRunning = true
                    true
                }
            }
        if (!shouldRun) return

        try {
            var more = true
            while (more) {
                runCursorStaleRecovery()
                more =
                    cursorStaleMutex.withLock {
                        if (cursorStalePending) {
                            cursorStalePending = false
                            true
                        } else {
                            cursorStaleRunning = false
                            false
                        }
                    }
            }
        } finally {
            // Normal exit already cleared cursorStaleRunning in the loop; this only fires when a
            // recovery threw or was cancelled, so a future CursorStale can still recover.
            if (cursorStaleRunning) {
                cursorStaleMutex.withLock {
                    cursorStaleRunning = false
                    cursorStalePending = false
                }
            }
        }
    }

    private suspend fun runCursorStaleRecovery() {
        logger.info { "CursorStale recovery — disconnect → catchUp → reseed → reconnect" }
        sseClient.disconnect()
        when (val result = catchUp.catchUpAll(registry)) {
            is AppResult.Success -> {}

            is AppResult.Failure -> {
                logger.warn {
                    "Catch-up failed during CursorStale recovery: ${result.error.code}; continuing to reconnect"
                }
            }
        }
        val newCursor = store.highestCursor()
        sseClient.reseed(newCursor)
        sseClient.connect()
    }

    /**
     * Recover from an `AccessChanged` control signal: the caller's accessible set may have
     * changed without a book row mutating (a collection was shared/unshared with them, a share's
     * permission changed, or a book was released into a collection they can see).
     *
     * For each access-gated domain (books plus the three collection domains) this **re-derives
     * and prunes** (approach B — always re-derive, no digest gate):
     *  1. Run a TRANSIENT access-filtered catch-up from cursor 0 via [CatchUp.catchUpTransient].
     *     The server's `pullSince` for these domains is access-filtered, so the pass upserts
     *     exactly the rows the caller may now see and returns their ids. It deliberately does
     *     NOT touch [SyncCursorStore] — the live SSE/cursor path continues independently.
     *  2. [AccessFilteredSyncHandler.pruneTo] soft-deletes every locally-live row NOT in that
     *     accessible set — the load-bearing security step that evicts a revoked share's now
     *     inaccessible books from Room (closing the gap a plain catch-up would leave open).
     *
     * For an admin, the access-filtered catch-up returns everything, so `pruneTo` deletes
     * nothing — the same code is correct for both members and admins.
     *
     * Unlike [handleCursorStale] this does not tear down the SSE connection: the live tail is
     * still valid (no cursor was lost), only the *visibility* of existing rows shifted. A failed
     * re-derive for one domain is logged and swallowed — the next signal (or a manual refresh)
     * recovers, and one domain's failure must not strand the others.
     */
    internal suspend fun handleAccessChanged() {
        logger.info { "AccessChanged: re-deriving + pruning access-gated domains" }
        for (handler in registry.accessFilteredHandlers()) {
            @Suppress("UNCHECKED_CAST")
            val typed = handler as SyncDomainHandler<Any>
            when (val ids = catchUp.catchUpTransient(typed)) {
                is AppResult.Success -> {
                    (handler as AccessFilteredSyncHandler).pruneTo(ids.data, currentEpochMilliseconds())
                }

                is AppResult.Failure -> {
                    logger.warn { "AccessChanged reconcile failed for ${handler.domainName}: ${ids.error.code}" }
                }
            }
        }
    }

    /** Stop SSE and wait until the frame collector is fully cancelled. Used by tests and deterministic shutdown paths. */
    suspend fun stopAndJoin() {
        startMutex.withLock {
            val engine = engineJob
            val collector = frameCollectorJob
            val connectionUp = connectionUpDrainJob
            val enqueue = enqueueDrainJob
            val depth = queueDepthJob
            val failure = failureCountJob
            engineJob = null
            currentUser = null
            currentUserStarted = false
            frameCollectorJob = null
            connectionUpDrainJob = null
            enqueueDrainJob = null
            queueDepthJob = null
            failureCountJob = null
            engine?.cancelAndJoin()
            collector?.cancelAndJoin()
            connectionUp?.cancelAndJoin()
            enqueue?.cancelAndJoin()
            depth?.cancelAndJoin()
            failure?.cancelAndJoin()
            sseClient.disconnect()
        }
    }

    private fun ensureFrameCollector() {
        if (frameCollectorJob?.isActive == true) return
        frameCollectorJob =
            scope.launch {
                sseClient.frames.collect { frame ->
                    dispatcher.handle(frame)
                }
            }
    }

    /**
     * Wire the two reactive triggers for [PendingOperationQueue.drain]:
     *
     *  1. Connection state transitions to [ConnectionState.Connected]. The
     *     queue may hold ops that landed while offline (or before sign-in
     *     completed). Draining on connect is what makes "local-first writes
     *     reach the server" actually work.
     *  2. New op enqueued. If we're already connected, drain immediately;
     *     otherwise the connection-up trigger will pick it up.
     *
     * The third trigger — backoff after retryable failure — lives inside
     * [runDrain] itself, scheduled per wave.
     *
     * The enqueue trigger uses [PendingOperationQueue.observeEnqueueSignal]'s
     * monotonic counter and `drop(1)` to ignore the replayed current value at
     * subscription time — we only care about *new* enqueues from this point
     * forward. The connection-up trigger uses [SyncEngineState.observe]'s
     * StateFlow directly because the current connection state is itself the
     * signal we want to react to.
     *
     * Suspends until both collectors are actively subscribed via
     * [onSubscription] — so a caller that enqueues immediately after
     * `start()` returns will reliably trigger a drain.
     */
    private suspend fun ensureDrainScheduling() {
        if (connectionUpDrainJob?.isActive != true) {
            val ready = CompletableDeferred<Unit>()
            connectionUpDrainJob =
                scope.launch {
                    state
                        .observe()
                        .onSubscription { ready.complete(Unit) }
                        // Collapse to a Boolean transition before distinctUntilChanged.
                        // Each parsed SSE frame produces a fresh `Connected(lastEventId=X)`
                        // value; data-class equality treats `Connected(1)` and
                        // `Connected(2)` as distinct, so `distinctUntilChanged` on the
                        // raw connection would let every frame through and schedule a
                        // drain per frame — a DB walk per SSE event. We only want to
                        // fire on the Disconnected/Connecting → Connected edge.
                        .map { it.connection is ConnectionState.Connected }
                        .distinctUntilChanged()
                        .filter { it }
                        .collect {
                            runDrain()
                            runCatching { downloadRepository.recheckWaitingForServer() }
                                .onFailure { e ->
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    logger.warn(e) { "recheckWaitingForServer failed on reconnect" }
                                }
                        }
                }
            ready.await()
        }
        if (enqueueDrainJob?.isActive != true) {
            val ready = CompletableDeferred<Unit>()
            enqueueDrainJob =
                scope.launch {
                    queue
                        .observeEnqueueSignal()
                        .onSubscription { ready.complete(Unit) }
                        .drop(1) // ignore the StateFlow's replayed current value
                        .filter { state.value.connection is ConnectionState.Connected }
                        .collect { runDrain() }
                }
            ready.await()
        }
    }

    /**
     * Forward the queue's depth and failure-count flows into [SyncEngineState]
     * so the diagnostics surface (Renovation §2.10) reflects reality. Without
     * this wire-up, [SyncEngineState.setQueueDepth] and
     * [SyncEngineState.setFailureCount] are dead writers — nothing invokes
     * them, and the snapshot fields stay at 0 forever.
     *
     * Suspends until both collectors have begun collecting via [onStart] so
     * callers observing state immediately after `start()` returns see the
     * live values, not the initial zeros. Room's `Flow<Int>` is a cold flow,
     * so `onStart` (not `onSubscription`, which is StateFlow/SharedFlow-only)
     * is the right hook to signal "we're collecting now."
     */
    private suspend fun ensureStateObservers() {
        if (queueDepthJob?.isActive != true) {
            val ready = CompletableDeferred<Unit>()
            queueDepthJob =
                scope.launch {
                    queue
                        .observeQueueDepth()
                        .onStart { ready.complete(Unit) }
                        .collect { depth -> state.setQueueDepth(depth) }
                }
            ready.await()
        }
        if (failureCountJob?.isActive != true) {
            val ready = CompletableDeferred<Unit>()
            failureCountJob =
                scope.launch {
                    queue
                        .observeFailureCount()
                        .onStart { ready.complete(Unit) }
                        .collect { count -> state.setFailureCount(count) }
                }
            ready.await()
        }
    }

    /**
     * Run one drain wave under [drainMutex] so concurrent triggers (connect-up,
     * enqueue, retry) coalesce. If the wave produced retryable failures,
     * schedule a backoff-delayed re-drain — that's the engine's retry timer.
     * Non-retryable failures stay flagged past `MAX_RETRYABLE_ATTEMPTS` and
     * are silently skipped on subsequent waves.
     */
    private suspend fun runDrain() {
        val outcome =
            drainMutex.withLock {
                try {
                    queue.drain()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Re-throwing here would tear down the trigger collector
                    // and silently stop all future drains. Log and continue —
                    // a future trigger will re-attempt.
                    logger.warn(e) { "Drain wave failed unexpectedly; will retry on next trigger" }
                    return
                }
            }
        if (outcome.hasRetryableFailures) {
            scope.launch {
                delay(retryBackoffMillis)
                runDrain()
            }
        }
    }

    private companion object {
        // Default retry backoff. Production wiring can override via constructor
        // if the threat model changes; this value pairs with the queue's
        // MAX_RETRYABLE_ATTEMPTS = 5 to bound total retry time to ~5s on
        // transient failures without hammering the server.
        const val DEFAULT_RETRY_BACKOFF_MILLIS = 1_000L
    }
}
