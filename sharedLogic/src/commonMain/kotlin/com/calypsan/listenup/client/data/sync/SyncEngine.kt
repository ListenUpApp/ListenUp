package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.sync.domains.RefreshedDomainRouter
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.core.currentEpochMilliseconds
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
internal class SyncEngine(
    private val registry: ClientSyncDomainRegistry,
    private val queue: PendingOperationQueue,
    private val state: SyncEngineState,
    private val store: SyncCursorStore,
    private val catchUp: CatchUp,
    private val sseClient: SseClient,
    private val reconciler: SyncReconciler,
    private val dispatcher: SyncEventDispatcher,
    private val presenceRefreshSignal: PresenceRefreshSignal,
    private val scope: CoroutineScope,
    // Gates the outbox drain on device reachability rather than the SSE firehose. The outbox pushes
    // over the RPC/request transport, which is independent of the inbound SSE stream — so a new op
    // (playback position, listening event, offline edit) must drain whenever the device is online,
    // even while the firehose is down. Gating on `isOnline()` (not "SSE Connected") is the fix; it
    // also spares the retry budget when the device is genuinely offline (no drain, no burned
    // attempts). Defaults to an always-online monitor so existing call sites that don't drive
    // connectivity (tests) need no change; production wires the live monitor.
    private val networkMonitor: NetworkMonitor = AlwaysOnlineNetworkMonitor,
    // The refreshed tier's router. The lifecycle-reconcile pass re-runs every refreshed domain's
    // declared refresh through it, so a dropped refresh trigger self-heals on the next foreground/reconnect
    // edge — derived from the catalog, no per-domain recovery wiring (Plan §6a).
    private val refreshedRouter: RefreshedDomainRouter = RefreshedDomainRouter(emptyList()),
    private val retryBackoffMillis: Long = DEFAULT_RETRY_BACKOFF_MILLIS,
    private val lifecycleReconcileMinIntervalMs: Long = LIFECYCLE_RECONCILE_MIN_INTERVAL_MS,
) {
    private var frameCollectorJob: Job? = null
    private var connectionUpDrainJob: Job? = null
    private var enqueueDrainJob: Job? = null
    private var queueDepthJob: Job? = null
    private var deadLetterCountJob: Job? = null
    private var reconnectRefreshJob: Job? = null

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

    // Completion signals for coalesced callers. A caller that finds a recovery already running
    // (e.g. `refreshListeningHistory()` right after a large ABS import, while start()'s reconcile or
    // a scan-completion recovery is still draining) must AWAIT a forward catch-up that runs at-or-
    // after its request — otherwise it returns before the imported rows reach Room (Continue
    // Listening / streak starved until a restart). Each coalesced caller registers a deferred here
    // while the leading recovery is in flight; the leading loop snapshots the outstanding waiters at
    // the START of every pass and completes them when that pass ENDS. Because a waiter is only
    // satisfied by a pass that began after it registered (and the pending flag guarantees that pass
    // runs), the caller suspends exactly until a catch-up pass covering its request has drained.
    private val cursorStaleWaiters = mutableListOf<CompletableDeferred<Unit>>()

    // Serializes + debounces the standing lifecycle reconcile — the one recovery pass every
    // lifecycle edge (app-foreground via connectRealtime, firehose reconnect) funnels into.
    // Distinct from [handleCursorStale]: the live SSE tail stays UP; only forward catch-up +
    // digest re-run. A pass within [LIFECYCLE_RECONCILE_MIN_INTERVAL_MS] of the last completed
    // pass is skipped unless force = true, so onResume storms and the cold-start double-run
    // (runStart already reconciled) collapse to nothing. A forced trigger arriving mid-pass
    // schedules exactly one covering follow-up so its effect (e.g. LibraryDataChanged) is never
    // lost to the coalescing.
    private val lifecycleReconcileMutex = Mutex()
    private var lifecycleReconcileRunning = false
    private var lifecycleReconcilePending = false

    // Debounce anchor for [lifecycleReconcile]. MONOTONIC, not wall-clock: the debounce is a
    // "duration since the last completed pass" comparison, and an NTP step (back OR forward) on a
    // wall clock would arbitrarily extend or collapse the suppression window. null means "no pass
    // has completed yet" → the first trigger is never debounced.
    private var lastLifecycleReconcileMark: TimeSource.Monotonic.ValueTimeMark? = null

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
     *    `enqueueDrainJob`, `queueDepthJob`, `deadLetterCountJob`,
     *    `reconnectRefreshJob` are launched into [scope], not as children of
     *    `engineJob`, so they survive the
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
        // Step 5b: refresh the Discover surfaces on every reconnect. Subscribed before connect so
        // the initial start-connect is the dropped first edge (start already primed + reconciled).
        ensureReconnectRefresh()
        // Step 6: forward queue-depth and dead-letter-count observers into
        // SyncEngineState so the diagnostics surface reflects reality. Without
        // this wire-up `pendingQueueDepth` / `deadLetterCount` stay stuck
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
        // Stamp the lifecycle-reconcile debounce clock: runStart just did the equivalent work
        // (forward catch-up + digest reconcile), so the connectRealtime() foreground reconcile
        // that immediately follows a cold start is debounced instead of redundantly re-draining.
        lifecycleReconcileMutex.withLock { lastLifecycleReconcileMark = TimeSource.Monotonic.markNow() }
    }

    fun stop() {
        // stop() is non-suspend so it can't take the startMutex. That means
        // the engineJob/currentUser writes below can race a concurrent start()
        // and lose: if start() is inside withLock between assigning currentUser
        // and assigning engineJob when stop() runs, stop's nulls land first and
        // start's writes overwrite them — net result is engine continues running
        // as if stop() never happened. This is acceptable because:
        //
        //   1. The production disconnect path does NOT call stop() — it goes
        //      through [SyncRepository.disconnect] → [stopAndJoin], which takes
        //      startMutex and join()s every collector, so it can't race a start().
        //      stop() is retained as the non-suspending soft variant for tests and
        //      any caller that doesn't need hard-shutdown semantics.
        //   2. Any caller that needs hard-shutdown semantics uses [stopAndJoin].
        //
        // If a caller is added later that calls stop() AND depends on its race
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
        deadLetterCountJob?.cancel()
        deadLetterCountJob = null
        reconnectRefreshJob?.cancel()
        reconnectRefreshJob = null
        sseClient.disconnect()
    }

    /**
     * Manually drop and re-establish the SSE firehose. Backs the offline-banner
     * "Retry" action: the automatic backoff loop may be mid-sleep when the user
     * regains connectivity, so we tear the connection down and immediately
     * re-open it. [SseClient.connect] resumes from the stored `Last-Event-Id`,
     * so no events are missed; the connection-state transition flows back through
     * [SyncEngineState] and drives the reachability indicator.
     */
    suspend fun reconnect() {
        sseClient.disconnect()
        sseClient.connect()
    }

    /**
     * Force a digest reconciliation across all registered domains, re-pulling any that
     * have drifted from the server. Unlike [start], this runs even when the engine is
     * already active — used after a server-side restore (a wholesale DB swap that is not
     * announced on the sync firehose, and which [start] cannot detect because it no-ops
     * when already running). [SyncReconciler.reconcileAll] is non-throwing and is the
     * same digest re-pull that [start] performs as its final step.
     */
    suspend fun forceReconcile() {
        reconciler.reconcileAll()
    }

    /**
     * The standing recovery pass every lifecycle edge funnels into — app-foreground (via
     * [com.calypsan.listenup.client.data.repository.SyncRepository.connectRealtime]) and firehose
     * reconnect ([runReconnectRefresh]). Removes the "restart required" failure class structurally:
     * anything a live event or control frame dropped while the app was backgrounded or the firehose
     * was down self-heals on the next edge.
     *
     * Ordering is load-bearing:
     *  1. **Forward catch-up FIRST** ([CatchUp.catchUpAll]) — drains rows written server-side ABOVE
     *     the client's cursor (exactly what a `FirehoseSuppressed` bulk write produces). The digest
     *     reconcile is blind to these: it fingerprints AT the cursor, so above-cursor rows are
     *     excluded from both sides' digests.
     *  2. **Digest reconcile SECOND** ([SyncReconciler.reconcileAll]) — repairs in-place divergence
     *     at/below the cursor.
     *  3. **Refreshed-tier recovery** — re-run every refreshed domain's refresh (presence, server-info,
     *     preferences) so a dropped control frame heals on this edge too.
     *
     * Coalesced + debounced: a pass within [LIFECYCLE_RECONCILE_MIN_INTERVAL_MS] of the last is
     * skipped unless [force] is true, so rapid foreground/reconnect edges collapse to one pass.
     * A [force] = true trigger arriving while a pass runs schedules exactly one covering follow-up.
     * Unlike [handleCursorStale] this never tears down the SSE connection — the live tail is healthy.
     */
    suspend fun lifecycleReconcile(force: Boolean = false) {
        val shouldLead =
            lifecycleReconcileMutex.withLock {
                when {
                    lifecycleReconcileRunning -> {
                        // A pass is already in flight. A forced trigger needs a covering follow-up so
                        // its effect isn't lost to coalescing; a debounced trigger folds into it.
                        if (force) lifecycleReconcilePending = true
                        false
                    }

                    force || debounceElapsedLocked() -> {
                        lifecycleReconcileRunning = true
                        true
                    }

                    else -> {
                        false
                    } // within the debounce window and not forced → drop
                }
            }
        if (!shouldLead) return
        // Set true ONLY on the loop's normal-exit branch, where this coroutine clears
        // running/pending under the lock and ends the loop. It gates the finally so cleanup is
        // ownership-scoped: a normally-completed leader must NOT touch shared state in finally,
        // because a follow-on leader may already own it (the cross-leader stomp this guards).
        var ledToCompletion = false
        try {
            var more = true
            while (more) {
                runLifecycleReconcilePass()
                more =
                    lifecycleReconcileMutex.withLock {
                        lastLifecycleReconcileMark = TimeSource.Monotonic.markNow()
                        if (lifecycleReconcilePending) {
                            lifecycleReconcilePending = false
                            true
                        } else {
                            lifecycleReconcileRunning = false
                            ledToCompletion = true
                            false
                        }
                    }
            }
        } finally {
            // Ownership-scoped cleanup. On normal completion the loop already cleared the flags under
            // the lock, so this does nothing — reaching in would let a returning leader stomp a NEW
            // leader's in-flight pass (and silently drop its forced follow-up). This reset therefore
            // fires ONLY when a pass threw or was cancelled before the loop's normal exit, and it does
            // the read+reset entirely under the lock so no unsynchronized flag read remains.
            if (!ledToCompletion) {
                // NonCancellable: this cleanup usually runs BECAUSE the pass was cancelled, and the
                // mutex may be momentarily contended by another trigger — a bare `withLock` would then
                // throw CancellationException and leave `running` stuck true forever, permanently
                // no-op'ing every future reconcile (the "restart required" class this engine kills).
                withContext(NonCancellable) {
                    lifecycleReconcileMutex.withLock {
                        lifecycleReconcileRunning = false
                        lifecycleReconcilePending = false
                    }
                }
            }
        }
    }

    /** Whether the debounce window has elapsed since the last completed pass. MUST hold [lifecycleReconcileMutex]. */
    private fun debounceElapsedLocked(): Boolean {
        val lastMark = lastLifecycleReconcileMark ?: return true
        return lastMark.elapsedNow() >= lifecycleReconcileMinIntervalMs.milliseconds
    }

    /** One lifecycle reconcile pass: forward catch-up → digest reconcile → refreshed-tier recovery. */
    private suspend fun runLifecycleReconcilePass() {
        when (val result = catchUp.catchUpAll(registry)) {
            is AppResult.Success -> {}

            is AppResult.Failure -> {
                logger.warn {
                    "Lifecycle catch-up failed: ${result.error.code}; continuing to digest reconcile"
                }
            }
        }
        // reconcileAll is non-throwing; the router guards each refresh individually.
        reconciler.reconcileAll()
        // Re-run every refreshed domain's declared refresh so a dropped refresh trigger (presence, server-info,
        // preferences) self-heals on this lifecycle edge — derived from the catalog, not hand-dispatched.
        refreshedRouter.refreshAll()
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
        // Coalesce: if a recovery is already running, request a single follow-up pass instead of
        // starting a concurrent catchUpAll, and AWAIT that follow-up so the caller does not return
        // before the rows its request needs have drained. The leading caller drains the pending flag
        // in a loop so a burst of triggers collapses to one follow-up pass, and completes each
        // coalesced waiter when the pass that began after its request ends.
        val coalescedWaiter =
            cursorStaleMutex.withLock {
                if (cursorStaleRunning) {
                    cursorStalePending = true
                    CompletableDeferred<Unit>().also { cursorStaleWaiters += it }
                } else {
                    cursorStaleRunning = true
                    null
                }
            }
        if (coalescedWaiter != null) {
            // Suspend until the leading loop's next pass (forced by the pending flag we just set)
            // completes and resolves this waiter. join() returns normally on cancellation, so a
            // cancelled recovery propagates through the leading caller, not here.
            coalescedWaiter.join()
            return
        }

        try {
            var more = true
            while (more) {
                // Snapshot the waiters registered BEFORE this pass starts. They are satisfied the
                // moment this pass ends — it began after their request, so it covers it.
                val satisfiedByThisPass = cursorStaleMutex.withLock { drainWaiters() }
                runCursorStaleRecovery()
                satisfiedByThisPass.forEach { it.complete(Unit) }
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
            // recovery threw or was cancelled, so a future CursorStale can still recover. Any waiters
            // still outstanding (their covering pass never completed) must be released so coalesced
            // callers are not stranded — cancelled, not completed, since their request was not served.
            if (cursorStaleRunning) {
                // NonCancellable for the same reason as lifecycleReconcile's cleanup: a contended
                // `withLock` on the cancellation path would throw and strand `cursorStaleRunning` true,
                // wedging all future CursorStale recovery.
                val stranded =
                    withContext(NonCancellable) {
                        cursorStaleMutex.withLock {
                            cursorStaleRunning = false
                            cursorStalePending = false
                            drainWaiters()
                        }
                    }
                stranded.forEach { it.cancel() }
            }
        }
    }

    /** Remove and return all outstanding cursor-stale waiters. MUST be called holding [cursorStaleMutex]. */
    private fun drainWaiters(): List<CompletableDeferred<Unit>> {
        if (cursorStaleWaiters.isEmpty()) return emptyList()
        val snapshot = cursorStaleWaiters.toList()
        cursorStaleWaiters.clear()
        return snapshot
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
        // A dropped-then-restored firehose may have missed an ActiveSessionsChanged nudge while
        // disconnected. Ping presence so the social repos re-fetch their ACL-filtered RPCs — the
        // Never-Stranded fallback for presence across a reconnect.
        presenceRefreshSignal.ping()
    }

    /**
     * Recover from an `AccessChanged` control signal: the caller's accessible set may have
     * changed without a book row mutating (a collection was shared/unshared with them, a share's
     * permission changed, or a book was released into a collection they can see).
     *
     * For each access-gated domain (`books`, `activities`, and the three collection domains) this **re-derives
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
            val deadLetters = deadLetterCountJob
            val reconnectRefresh = reconnectRefreshJob
            engineJob = null
            currentUser = null
            currentUserStarted = false
            frameCollectorJob = null
            connectionUpDrainJob = null
            enqueueDrainJob = null
            queueDepthJob = null
            deadLetterCountJob = null
            reconnectRefreshJob = null
            engine?.cancelAndJoin()
            collector?.cancelAndJoin()
            connectionUp?.cancelAndJoin()
            enqueue?.cancelAndJoin()
            depth?.cancelAndJoin()
            deadLetters?.cancelAndJoin()
            reconnectRefresh?.cancelAndJoin()
            sseClient.disconnect()
        }
    }

    private fun ensureFrameCollector() {
        if (frameCollectorJob?.isActive == true) return
        frameCollectorJob =
            scope.launch {
                sseClient.frames.collect { frame ->
                    // Guard per frame so one bad event logs and the firehose collector keeps
                    // running, rather than dying (sync stops) or killing the process on K/N.
                    try {
                        dispatcher.handle(frame)
                    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn(e) { "SSE frame handling failed; firehose collector continues" }
                    }
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
                        // Gate on device reachability, NOT the SSE firehose. The outbox pushes over the
                        // RPC/request transport, which is independent of the inbound SSE stream — so a new
                        // op must drain whenever the device is online, even while the firehose is down (its
                        // reconnect loop, an outage, etc.). Gating on SSE-Connected silently stranded
                        // playback progress + edits whenever the firehose wasn't up. When offline we skip
                        // the drain so the op's retry budget isn't burned against an unreachable server;
                        // the connection-up trigger + reconnection supervisor recover it on the next edge.
                        .filter { networkMonitor.isOnline() }
                        .collect { runDrain() }
                }
            ready.await()
        }
    }

    /**
     * Refresh the Discover surfaces on every reconnect — NOT the initial start-connect.
     *
     * The offline→online path ([ReconnectionSupervisor] → [SseClient.reconnectNow]) only resumes the
     * SSE stream; without this the leaderboard stays stale until the server happens to emit a
     * `CursorStale`. On each reconnect edge we run [lifecycleReconcile] — forward catch-up (draining
     * rows written above the cursor during the outage), digest reconcile (refreshing
     * `public_profiles` → the leaderboard), and a presence ping so currently-listening re-fetches.
     * The activity feed is now a Room-mirrored sync domain, so catch-up/live-tail keep it current
     * with no separate prime.
     *
     * Subscribed before [SseClient.connect] and [drop]ping the first Connected, so the initial
     * start-connect (already covered by [runStart]'s reconcile) does not double-fire.
     */
    private suspend fun ensureReconnectRefresh() {
        if (reconnectRefreshJob?.isActive == true) return
        val ready = CompletableDeferred<Unit>()
        reconnectRefreshJob =
            scope.launch {
                state
                    .observe()
                    .onSubscription { ready.complete(Unit) }
                    .map { it.connection is ConnectionState.Connected }
                    .distinctUntilChanged()
                    .filter { it }
                    .drop(1) // skip the initial start-connect; runStart already primed + reconciled
                    .collect { runReconnectRefresh() }
            }
        ready.await()
    }

    private suspend fun runReconnectRefresh() {
        // A reconnect may have missed live events while the firehose was down. Funnel into the one
        // lifecycle reconcile: forward catch-up drains rows written above the cursor during the
        // outage (the digest can't see them), digest repairs below-cursor divergence, and the
        // presence ping re-fetches currently-listening. force = true bypasses the debounce so a
        // reconnect edge always heals.
        lifecycleReconcile(force = true)
    }

    /**
     * Forward the queue's depth and dead-letter-count flows into [SyncEngineState]
     * so the diagnostics surface (Renovation §2.10) reflects reality. Without
     * this wire-up, [SyncEngineState.setQueueDepth] and
     * [SyncEngineState.setDeadLetterCount] are dead writers — nothing invokes
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
                        .collect { depth ->
                            try {
                                state.setQueueDepth(depth)
                            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                logger.warn(e) { "Queue-depth observer failed; engine continues" }
                            }
                        }
                }
            ready.await()
        }
        if (deadLetterCountJob?.isActive != true) {
            val ready = CompletableDeferred<Unit>()
            deadLetterCountJob =
                scope.launch {
                    queue
                        .observeDeadLetterCount()
                        .onStart { ready.complete(Unit) }
                        .collect { count ->
                            try {
                                state.setDeadLetterCount(count)
                            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                logger.warn(e) { "Dead-letter-count observer failed; engine continues" }
                            }
                        }
                }
            ready.await()
        }
    }

    /**
     * Drain the outbox to empty in response to a single trigger (connect-up,
     * enqueue, retry). A single [PendingOperationQueue.drain] wave dispatches
     * only one op per (domain, entityId) group, so a per-entity backlog of N
     * queued ops needs N waves to fully drain — loop immediately as long as a
     * wave made progress (something sent or terminally quarantined) and more
     * dispatchable ops remain beyond this wave's own retryable failures.
     *
     * Each wave runs under [drainMutex] so concurrent triggers (connect-up,
     * enqueue, retry) coalesce. If the loop exits with retryable failures still
     * outstanding, schedule a backoff-delayed re-drain — that's the engine's
     * retry timer. Non-retryable failures stay flagged past
     * `MAX_RETRYABLE_ATTEMPTS` and are silently skipped on subsequent waves.
     */
    private suspend fun runDrain() {
        while (true) {
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
            val madeProgress = outcome.sent > 0 || outcome.terminalFailures > 0
            val backlogBeyondThisWavesFailures = outcome.remainingDispatchable > outcome.retryableFailures
            if (madeProgress && backlogBeyondThisWavesFailures) continue
            if (outcome.hasRetryableFailures) {
                scope.launch {
                    delay(retryBackoffMillis)
                    runDrain()
                }
            }
            return
        }
    }

    private companion object {
        // Default retry backoff. Production wiring can override via constructor
        // if the threat model changes; this value pairs with the queue's
        // MAX_RETRYABLE_ATTEMPTS = 5 to bound total retry time to ~5s on
        // transient failures without hammering the server.
        const val DEFAULT_RETRY_BACKOFF_MILLIS = 1_000L

        // Debounce floor for [lifecycleReconcile]. Guards against onResume storms: a full pass is
        // ~20 cheap catch-up GETs (empty pages when idle) + ~20 digest GETs — fine on a genuine
        // edge, wasteful at 1 Hz. force = true (reconnect, LibraryDataChanged) bypasses it.
        const val LIFECYCLE_RECONCILE_MIN_INTERVAL_MS = 30_000L
    }
}

/**
 * Fallback [NetworkMonitor] that always reports online. Defaults [SyncEngine]'s monitor so unit tests
 * that don't exercise offline gating need no change; production always wires the live platform
 * monitor via Koin.
 */
private object AlwaysOnlineNetworkMonitor : NetworkMonitor {
    override fun isOnline(): Boolean = true

    override val isOnlineFlow: StateFlow<Boolean> = MutableStateFlow(true)

    override val isOnUnmeteredNetworkFlow: StateFlow<Boolean> = MutableStateFlow(true)
}
