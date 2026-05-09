package com.calypsan.listenup.client.data.sync

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
 * Frame collection (piping `sseClient.frames` through the dispatcher) is wired
 * by the Koin module at app start time, NOT here.
 */
class SyncEngine(
    private val registry: ClientSyncDomainRegistry,
    private val queue: PendingOperationQueue,
    @Suppress("UnusedPrivateProperty") private val state: SyncEngineState,
    private val store: SyncCursorStore,
    private val catchUp: CatchUp,
    private val sseClient: SseClient,
) {
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
        // Step 4: connect SSE.
        sseClient.connect()
    }

    fun stop() {
        sseClient.disconnect()
    }
}
