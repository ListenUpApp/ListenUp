package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.sync.domains.OpKind
import com.calypsan.listenup.client.data.sync.domains.OutboxChannel
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.serializer

private const val TIMEOUT_SECONDS = 5L

// "tags" is not a real outbox channel — a minimal local fixture for a hypothetical
// un-mirrored domain, matching the queue's payload-agnostic contract.
private val tagsChannel = OutboxChannel("tags", String.serializer(), setOf(OpKind.Upsert), idempotent = true)

/**
 * Verifies `SyncEngine` forwards `PendingOperationQueue.observeQueueDepth()` and
 * `observeDeadLetterCount()` into [SyncEngineState] so the diagnostics surface
 * reflects reality. Pre-fix: `setQueueDepth`/`setDeadLetterCount` were dead writers
 * — nothing ever invoked them, so `pendingQueueDepth` / `deadLetterCount`
 * were stuck at 0 forever.
 */
class SyncEngineStateObserversTest :
    FunSpec({

        test("SyncEngineState.pendingQueueDepth reflects PendingOperationQueue.observeQueueDepth") {
            runBlocking {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val db = createInMemoryTestDatabase()
                try {
                    // Retryable failure keeps ops in the queue (failureCount increments but
                    // stays within budget, so the op remains dispatchable). Using a Success
                    // sender would drain-and-delete the op the moment the engine sees the
                    // enqueue signal, racing the assertion; a non-retryable failure would
                    // flip the op to a dead letter, which observeQueueDepth now excludes.
                    val queue =
                        PendingOperationQueue(
                            dao = db.pendingOperationV2Dao(),
                            sender =
                                PendingOperationSender {
                                    AppResult.Failure(
                                        com.calypsan.listenup.api.error.TransportError
                                            .NetworkUnavailable(),
                                    )
                                },
                        )
                    val state = SyncEngineState()
                    val sse = FakeStateSseClient(state)
                    val engine = buildEngine(db, queue, state, sse, scope)

                    engine.start(currentUserId = "u1")

                    state.value.pendingQueueDepth shouldBe 0

                    queue.enqueue(tagsChannel, "t1", OpKind.Upsert, "{}", "u1")
                    queue.enqueue(tagsChannel, "t2", OpKind.Upsert, "{}", "u1")

                    val expectedDepth = 2
                    withTimeout(TIMEOUT_SECONDS.seconds) {
                        state.observe().first { it.pendingQueueDepth == expectedDepth }
                    }
                } finally {
                    scope.cancel()
                    scope.coroutineContext.job.children
                        .forEach { it.join() }
                    db.close()
                }
            }
        }

        test("SyncEngineState.deadLetterCount reflects PendingOperationQueue.observeDeadLetterCount") {
            runBlocking {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val db = createInMemoryTestDatabase()
                try {
                    val queue =
                        PendingOperationQueue(
                            dao = db.pendingOperationV2Dao(),
                            sender = PendingOperationSender { AppResult.Success(Unit) },
                        )
                    val state = SyncEngineState()
                    val sse = FakeStateSseClient(state)
                    val engine = buildEngine(db, queue, state, sse, scope)

                    engine.start(currentUserId = "u1")

                    // Insert a row directly via the DAO with failureCount already
                    // past the retry budget — bypassing PendingOperationQueue.enqueue
                    // (which would race the drain trigger and delete the op on the
                    // success path before we could mutate it). The DAO row is the
                    // invariant the failure-count flow watches, so writing to it
                    // directly is the canonical way to assert the observer wire-up.
                    val pastMax = 99
                    db
                        .pendingOperationV2Dao()
                        .insert(
                            com.calypsan.listenup.client.data.local.db.PendingOperationV2Entity(
                                clientOpId = "failed-op",
                                domainName = "tags",
                                entityId = "t1",
                                opType = "upsert",
                                payload = "{}",
                                enqueuedAt = 1_000L,
                                lastAttemptAt = 1_000L,
                                failureCount = pastMax,
                                lastError = "test",
                                ownerUserId = "u1",
                            ),
                        )

                    withTimeout(TIMEOUT_SECONDS.seconds) {
                        state.observe().first { it.deadLetterCount == 1 }
                    }
                } finally {
                    scope.cancel()
                    scope.coroutineContext.job.children
                        .forEach { it.join() }
                    db.close()
                }
            }
        }
    })

private fun buildEngine(
    db: ListenUpDatabase,
    queue: PendingOperationQueue,
    state: SyncEngineState,
    sse: SseClient,
    scope: CoroutineScope,
): SyncEngine {
    val registry = ClientSyncDomainRegistry()
    val store = SyncCursorStore(db.syncCursorDao())
    val dispatcher =
        SyncEventDispatcher(
            registry = registry,
            state = state,
            cursorAdvance = { domain, rev -> store.setCursor(domain, rev) },
        )
    return SyncEngine(
        registry = registry,
        queue = queue,
        state = state,
        store = store,
        catchUp = NoopStateCatchUp,
        sseClient = sse,
        reconciler = noopSyncReconciler(registry, store, NoopStateCatchUp),
        dispatcher = dispatcher,
        presenceRefreshSignal = PresenceRefreshSignal(),
        scope = scope,
    )
}

private object NoopStateCatchUp : CatchUp {
    override suspend fun <T : Any> catchUp(handler: SyncDomainHandler<T>): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun <T : Any> catchUpFromZero(handler: SyncDomainHandler<T>): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun catchUpAll(registry: ClientSyncDomainRegistry): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun <T : Any> catchUpTransient(handler: SyncDomainHandler<T>): AppResult<Set<String>> = AppResult.Success(emptySet())

    override suspend fun domains(): AppResult<List<String>> = AppResult.Success(emptyList())
}

/**
 * Fake SSE client mirroring production's `connect()`-flips-state semantics.
 * Local to this test so test fakes don't leak between files.
 */
private class FakeStateSseClient(
    private val state: SyncEngineState,
) : SseClient {
    private val flow = MutableSharedFlow<ParsedSseFrame>()
    override val frames: SharedFlow<ParsedSseFrame> = flow.asSharedFlow()

    private var seeded: Long? = null

    override fun seedLastEventId(initial: Long?) {
        seeded = initial
    }

    override fun connect() {
        state.setConnection(ConnectionState.Connected(lastEventId = seeded))
    }

    override fun disconnect() {
        state.setConnection(ConnectionState.Disconnected(reason = "test"))
    }

    override fun currentLastEventId(): Long? = seeded

    override suspend fun reseed(newLastEventId: Long?) {
        disconnect()
        seeded = newLastEventId
    }

    override fun reconnectNow() = Unit
}
