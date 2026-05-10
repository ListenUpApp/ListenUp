package com.calypsan.listenup.client.data.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
    @Suppress("UnusedPrivateProperty") private val state: SyncEngineState,
    private val store: SyncCursorStore,
    private val catchUp: CatchUp,
    private val sseClient: SseClient,
    private val dispatcher: SyncEventDispatcher,
    private val scope: CoroutineScope,
) {
    private var frameCollectorJob: Job? = null

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
        // Step 5: connect SSE.
        sseClient.connect()
    }

    fun stop() {
        frameCollectorJob?.cancel()
        frameCollectorJob = null
        sseClient.disconnect()
    }

    /** Stop SSE and wait until the frame collector is fully cancelled. Used by tests and deterministic shutdown paths. */
    suspend fun stopAndJoin() {
        val collector = frameCollectorJob
        frameCollectorJob = null
        collector?.cancelAndJoin()
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
}
