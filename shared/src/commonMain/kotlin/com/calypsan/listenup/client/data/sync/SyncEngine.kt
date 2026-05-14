package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.client.core.AppResult
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
    private val dispatcher: SyncEventDispatcher,
    private val scope: CoroutineScope,
    private val retryBackoffMillis: Long = DEFAULT_RETRY_BACKOFF_MILLIS,
) {
    private var frameCollectorJob: Job? = null
    private var connectionUpDrainJob: Job? = null
    private var enqueueDrainJob: Job? = null

    // Serializes drain waves so concurrent triggers (connection-up + enqueue +
    // retry) coalesce into one wave at a time. drain() reads from the DAO and
    // mutates rows; without a Mutex two concurrent drains would dispatch the
    // same op twice. The Mutex is per-engine, not per-op — per-op FIFO is the
    // queue's SQL filter responsibility.
    private val drainMutex = Mutex()

    /**
     * Start the engine for [currentUserId]. Re-calling re-runs catch-up;
     * proper idempotency arrives in D1 when frame collection is wired in.
     */
    suspend fun start(currentUserId: String) {
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
        // Step 6: connect SSE.
        sseClient.connect()
    }

    fun stop() {
        frameCollectorJob?.cancel()
        frameCollectorJob = null
        connectionUpDrainJob?.cancel()
        connectionUpDrainJob = null
        enqueueDrainJob?.cancel()
        enqueueDrainJob = null
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
    internal suspend fun handleCursorStale(lastKnown: Long?) {
        logger.info { "CursorStale received; lastKnown=$lastKnown — disconnect → catchUp → reseed → reconnect" }
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

    /** Stop SSE and wait until the frame collector is fully cancelled. Used by tests and deterministic shutdown paths. */
    suspend fun stopAndJoin() {
        val collector = frameCollectorJob
        val connectionUp = connectionUpDrainJob
        val enqueue = enqueueDrainJob
        frameCollectorJob = null
        connectionUpDrainJob = null
        enqueueDrainJob = null
        collector?.cancelAndJoin()
        connectionUp?.cancelAndJoin()
        enqueue?.cancelAndJoin()
        sseClient.disconnect()
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
                        .map { it.connection }
                        .distinctUntilChanged()
                        .collect { connection ->
                            if (connection is ConnectionState.Connected) {
                                runDrain()
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
