package com.calypsan.listenup.client.data.sync.testing

import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.SyncEngine
import com.calypsan.listenup.client.data.sync.SyncEngineState
import com.calypsan.listenup.client.data.sync.SyncEventDispatcher
import com.calypsan.listenup.server.sync.TagRepository

/**
 * Test scope exposing engine internals for assertions. Filled in by Task C3
 * with the lifecycle wiring (in-memory Room DB, server testApplication, frame
 * collection coroutine).
 *
 * @property engine the started client engine (catch-up complete, SSE
 *   connecting/connected at scope-entry time)
 * @property recording the test handler observing tag events for assertions
 * @property tagRepo the server-side tag repository for triggering writes
 *   server-side
 * @property state observable engine state for ambient assertions
 * @property dispatcher the dispatcher routing SSE frames to handlers
 * @property queue the pending-operation queue for echo-match scenarios
 */
data class ClientEngineScope(
    val engine: SyncEngine,
    val recording: RecordingTagSyncDomainHandler,
    val tagRepo: TagRepository,
    val state: SyncEngineState,
    val dispatcher: SyncEventDispatcher,
    val queue: PendingOperationQueue,
)

/**
 * Boots `:server`'s test application AND the client engine in one process,
 * with a real in-memory Room DB on the client side. Use for Tier 3 e2e tests.
 *
 * **Skeleton — not implemented yet.** Task C3 fills in the body alongside the
 * actual e2e test cases. Calling this before C3 lands fails loudly via
 * [error] with a pointer to the task that completes the wiring.
 *
 * @param ignored the test body — accepted by the final signature so callers
 *   can be written against the real shape ahead of C3, but discarded here
 *   because the fixture is not yet wired.
 */
fun withClientSyncEngineAgainstServer(ignored: suspend ClientEngineScope.() -> Unit) {
    error(
        "WithClientSyncEngineAgainstServer body lands in Task C3; this skeleton " +
            "exists so RecordingTagSyncDomainHandler can land independently.",
    )
}
