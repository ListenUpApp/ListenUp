package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.sync.domains.OpKind
import com.calypsan.listenup.client.data.sync.domains.OutboxChannel
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.serializer

// "tags" is not a real outbox channel — a minimal local fixture for a hypothetical
// un-mirrored domain, matching the queue's payload-agnostic contract.
private val tagsChannel = OutboxChannel("tags", String.serializer(), setOf(OpKind.Upsert), idempotent = true)

private const val TIMEOUT_SECONDS = 5L
private const val RETRY_TIMEOUT_SECONDS = 10L
private const val MIN_RETRY_ATTEMPTS = 2
private const val POLL_DELAY_MILLIS = 20L

/**
 * Verifies `SyncEngine` schedules `PendingOperationQueue.drain()` on the three
 * triggers the queue's KDoc promises:
 *
 *  1. Connection state transitions to [ConnectionState.Connected].
 *  2. A new op is enqueued while the engine is already connected.
 *  3. After a drain that produced retryable failures, the engine reschedules
 *     a drain on a backoff so transient failures don't strand the op.
 *
 * Pre-fix: `drain()` existed but the engine never scheduled it. Local-first
 * writes scaffolded by the Renovation could never reach the server.
 */
class PendingQueueDrainSchedulingTest :
    FunSpec({

        test("drain is scheduled when connection transitions to Connected") {
            runBlocking {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val db = createInMemoryTestDatabase()
                try {
                    // replay = 64 so a late `sent.first { ... }` after the drain
                    // emit still sees the value; without replay, the emit could
                    // happen before the test's first() subscribes and be lost.
                    val sent = MutableSharedFlow<String>(replay = 64)
                    val sender =
                        PendingOperationSender { op ->
                            sent.tryEmit(op.clientOpId)
                            AppResult.Success(Unit)
                        }
                    val queue =
                        PendingOperationQueue(
                            dao = db.pendingOperationV2Dao(),
                            sender = sender,
                        )
                    val state = SyncEngineState()
                    val sse = FakeSseClient(state)
                    val engine = buildEngine(db, queue, state, sse, scope)

                    // Op already enqueued before engine.start — so the only thing
                    // that can drain it is the connection-up trigger.
                    val opId = queue.enqueue(tagsChannel, "t1", OpKind.Upsert, "{}", "u1")

                    engine.start(currentUserId = "u1")

                    withTimeout(TIMEOUT_SECONDS.seconds) {
                        sent.first { it == opId }
                    }
                } finally {
                    // Order matters: cancel-and-join the scope BEFORE closing
                    // the DB. Closing the DB while a runDrain is mid-transaction
                    // crashes the native SQLite driver (SIGSEGV inside
                    // sqlite3Close).
                    scope.cancel()
                    scope.coroutineContext.job.children
                        .forEach { it.join() }
                    db.close()
                }
            }
        }

        test("drain is scheduled when an op is enqueued while already connected") {
            runBlocking {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val db = createInMemoryTestDatabase()
                try {
                    // replay = 64 so a late `sent.first { ... }` after the drain
                    // emit still sees the value; without replay, the emit could
                    // happen before the test's first() subscribes and be lost.
                    val sent = MutableSharedFlow<String>(replay = 64)
                    val sender =
                        PendingOperationSender { op ->
                            sent.tryEmit(op.clientOpId)
                            AppResult.Success(Unit)
                        }
                    val queue =
                        PendingOperationQueue(
                            dao = db.pendingOperationV2Dao(),
                            sender = sender,
                        )
                    val state = SyncEngineState()
                    val sse = FakeSseClient(state)
                    val engine = buildEngine(db, queue, state, sse, scope)

                    engine.start(currentUserId = "u1")

                    // Wait until the engine is observably Connected before enqueuing,
                    // so the assertion is "enqueue-while-connected triggers drain"
                    // and not "connection-up trigger happens to fire."
                    withTimeout(TIMEOUT_SECONDS.seconds) {
                        state.observe().first { it.connection is ConnectionState.Connected }
                    }

                    val opId = queue.enqueue(tagsChannel, "t-late", OpKind.Upsert, "{}", "u1")

                    withTimeout(TIMEOUT_SECONDS.seconds) {
                        sent.first { it == opId }
                    }
                } finally {
                    // Order matters: cancel-and-join the scope BEFORE closing
                    // the DB. Closing the DB while a runDrain is mid-transaction
                    // crashes the native SQLite driver (SIGSEGV inside
                    // sqlite3Close).
                    scope.cancel()
                    scope.coroutineContext.job.children
                        .forEach { it.join() }
                    db.close()
                }
            }
        }

        test("drain fires once per Connected transition, not per Connected re-emission with different lastEventId") {
            runBlocking {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val db = createInMemoryTestDatabase()
                try {
                    // Wrap the DAO so we can count `nextDispatchable()` calls —
                    // each call corresponds 1:1 with a `runDrain()` wave inside
                    // the engine. Counting sends doesn't distinguish the bug
                    // (re-emissions only walk an empty queue and produce zero
                    // sends), so we count drain waves directly.
                    val dispatchCalls = AtomicInteger(0)
                    val dao = CountingDao(db.pendingOperationV2Dao(), dispatchCalls)
                    val sender = PendingOperationSender { AppResult.Success(Unit) }
                    val queue = PendingOperationQueue(dao = dao, sender = sender)
                    val state = SyncEngineState()
                    val sse = FakeSseClient(state)
                    val engine = buildEngine(db, queue, state, sse, scope)

                    engine.start(currentUserId = "u1")

                    // Wait until the engine is observably Connected and the
                    // first connection-up drain wave has executed. After this,
                    // dispatchCalls == 1.
                    withTimeout(TIMEOUT_SECONDS.seconds) {
                        state.observe().first { it.connection is ConnectionState.Connected }
                        while (dispatchCalls.get() < 1) {
                            delay(POLL_DELAY_MILLIS)
                        }
                    }
                    val baseline = dispatchCalls.get()

                    // Emit two more `Connected(lastEventId=X)` snapshots with
                    // different lastEventId values — simulating SSE frame arrivals
                    // that bump the cursor. Data-class equality treats these as
                    // distinct from the previous `Connected`, so a naive
                    // distinctUntilChanged on the raw connection lets them
                    // through and the engine schedules a drain per frame —
                    // a DB query per SSE frame on a busy firehose.
                    state.setConnection(ConnectionState.Connected(lastEventId = 1L))
                    state.setConnection(ConnectionState.Connected(lastEventId = 2L))

                    // Give the engine plenty of time to (incorrectly) re-fire drain.
                    delay(POLL_DELAY_MILLIS * 10)

                    // The two re-emissions must NOT trigger additional drain
                    // waves: the trigger fires once per transition into
                    // Connected, not per Connected snapshot.
                    dispatchCalls.get() shouldBe baseline
                } finally {
                    // Order matters: cancel-and-join the scope BEFORE closing
                    // the DB. Closing the DB while a runDrain is mid-transaction
                    // crashes the native SQLite driver (SIGSEGV inside
                    // sqlite3Close).
                    scope.cancel()
                    scope.coroutineContext.job.children
                        .forEach { it.join() }
                    db.close()
                }
            }
        }

        test("drain is rescheduled after retry backoff when a send fails retryably") {
            runBlocking {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val db = createInMemoryTestDatabase()
                try {
                    val attempts = AtomicInteger(0)
                    val attemptIds = mutableListOf<String>()
                    val attemptsMutex = kotlinx.coroutines.sync.Mutex()
                    val sender =
                        PendingOperationSender { op ->
                            val n = attempts.incrementAndGet()
                            attemptsMutex.lock()
                            try {
                                attemptIds += op.clientOpId
                            } finally {
                                attemptsMutex.unlock()
                            }
                            if (n == 1) {
                                AppResult.Failure(TransportError.NetworkUnavailable())
                            } else {
                                AppResult.Success(Unit)
                            }
                        }
                    val queue =
                        PendingOperationQueue(
                            dao = db.pendingOperationV2Dao(),
                            sender = sender,
                        )
                    val state = SyncEngineState()
                    val sse = FakeSseClient(state)
                    // Short backoff so the test doesn't have to wait long.
                    val engine =
                        buildEngine(db, queue, state, sse, scope, retryBackoffMillis = 50L)

                    val opId = queue.enqueue(tagsChannel, "t-retry", OpKind.Upsert, "{}", "u1")

                    engine.start(currentUserId = "u1")

                    withTimeout(RETRY_TIMEOUT_SECONDS.seconds) {
                        while (attempts.get() < MIN_RETRY_ATTEMPTS) {
                            delay(POLL_DELAY_MILLIS)
                        }
                    }
                    attempts.get() shouldBeGreaterThanOrEqualTo MIN_RETRY_ATTEMPTS
                    attemptIds shouldContain opId
                    // The op was successfully sent on the retry, so the row is gone.
                    db.pendingOperationV2Dao().get(opId) shouldBe null
                } finally {
                    // Order matters: cancel-and-join the scope BEFORE closing
                    // the DB. Closing the DB while a runDrain is mid-transaction
                    // crashes the native SQLite driver (SIGSEGV inside
                    // sqlite3Close).
                    scope.cancel()
                    scope.coroutineContext.job.children
                        .forEach { it.join() }
                    db.close()
                }
            }
        }

        test("an op enqueued while the SSE firehose is DOWN still drains when the device is online") {
            runBlocking {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val db = createInMemoryTestDatabase()
                try {
                    val sent = MutableSharedFlow<String>(replay = 64)
                    val sender =
                        PendingOperationSender { op ->
                            sent.tryEmit(op.clientOpId)
                            AppResult.Success(Unit)
                        }
                    val queue =
                        PendingOperationQueue(
                            dao = db.pendingOperationV2Dao(),
                            sender = sender,
                        )
                    val state = SyncEngineState()
                    // Firehose never connects; the outbox rides the RPC/request transport, which is up.
                    val sse = NeverConnectingSseClient(state)
                    val engine =
                        buildEngine(db, queue, state, sse, scope, networkMonitor = FakeNetworkMonitor(online = true))

                    engine.start(currentUserId = "u1")

                    // The firehose is observably NOT connected — proving the drain is not riding an SSE
                    // Connected edge. Playback progress etc. must still push over RPC.
                    (state.value.connection is ConnectionState.Connected) shouldBe false

                    val opId = queue.enqueue(tagsChannel, "t-offline-sse", OpKind.Upsert, "{}", "u1")

                    withTimeout(TIMEOUT_SECONDS.seconds) {
                        sent.first { it == opId }
                    }
                    db.pendingOperationV2Dao().get(opId) shouldBe null
                } finally {
                    scope.cancel()
                    scope.coroutineContext.job.children
                        .forEach { it.join() }
                    db.close()
                }
            }
        }

        test("an op enqueued while the device is OFFLINE does not drain (retry budget preserved)") {
            runBlocking {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val db = createInMemoryTestDatabase()
                try {
                    val attempts = AtomicInteger(0)
                    val sender =
                        PendingOperationSender { _ ->
                            attempts.incrementAndGet()
                            AppResult.Failure(TransportError.NetworkUnavailable())
                        }
                    val queue =
                        PendingOperationQueue(
                            dao = db.pendingOperationV2Dao(),
                            sender = sender,
                        )
                    val state = SyncEngineState()
                    val sse = NeverConnectingSseClient(state)
                    // Device offline: draining now would only burn the op's retry budget against an
                    // unreachable server — the outbox must hold until connectivity returns.
                    val engine =
                        buildEngine(db, queue, state, sse, scope, networkMonitor = FakeNetworkMonitor(online = false))

                    engine.start(currentUserId = "u1")

                    val opId = queue.enqueue(tagsChannel, "t-offline", OpKind.Upsert, "{}", "u1")

                    // Give the engine ample time to (wrongly) attempt a drain.
                    delay(POLL_DELAY_MILLIS * 10)

                    attempts.get() shouldBe 0
                    // The op is still queued, its retry budget untouched, ready for the next online edge.
                    db.pendingOperationV2Dao().get(opId)?.failureCount shouldBe 0
                } finally {
                    scope.cancel()
                    scope.coroutineContext.job.children
                        .forEach { it.join() }
                    db.close()
                }
            }
        }

        test("a multi-op backlog for one entity fully drains after a single Connected transition") {
            runBlocking {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val db = createInMemoryTestDatabase()
                try {
                    val sent = MutableSharedFlow<String>(replay = 64)
                    val sender =
                        PendingOperationSender { op ->
                            sent.tryEmit(op.clientOpId)
                            AppResult.Success(Unit)
                        }
                    val clock = AtomicLong(0)
                    val queue =
                        PendingOperationQueue(
                            dao = db.pendingOperationV2Dao(),
                            sender = sender,
                            nowMillis = { clock.incrementAndGet() },
                        )
                    val state = SyncEngineState()
                    val sse = FakeSseClient(state)
                    val engine = buildEngine(db, queue, state, sse, scope)
                    val a = queue.enqueue(tagsChannel, "t1", OpKind.Upsert, "{}", "u1")
                    val b = queue.enqueue(tagsChannel, "t1", OpKind.Upsert, "{}", "u1")
                    val c = queue.enqueue(tagsChannel, "t1", OpKind.Upsert, "{}", "u1")
                    engine.start(currentUserId = "u1")
                    withTimeout(TIMEOUT_SECONDS.seconds) { sent.first { it == c } }
                    db.pendingOperationV2Dao().get(a) shouldBe null
                    db.pendingOperationV2Dao().get(b) shouldBe null
                    db.pendingOperationV2Dao().get(c) shouldBe null
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
    db: com.calypsan.listenup.client.data.local.db.ListenUpDatabase,
    queue: PendingOperationQueue,
    state: SyncEngineState,
    sse: SseClient,
    scope: CoroutineScope,
    retryBackoffMillis: Long = 1_000L,
    networkMonitor: com.calypsan.listenup.client.domain.repository.NetworkMonitor = FakeNetworkMonitor(online = true),
): SyncEngine {
    val registry = ClientSyncDomainRegistry()
    registry.register(NoopTagHandler)
    val store = SyncCursorStore(db.syncCursorDao())
    val dispatcher =
        SyncEventDispatcher(
            registry = registry,
            queue = queue,
            state = state,
            cursorAdvance = { domain, rev -> store.setCursor(domain, rev) },
        )
    return SyncEngine(
        registry = registry,
        queue = queue,
        state = state,
        store = store,
        catchUp = NoopCatchUp,
        sseClient = sse,
        reconciler = noopSyncReconciler(registry, store, NoopCatchUp),
        dispatcher = dispatcher,
        presenceRefreshSignal = PresenceRefreshSignal(),
        scope = scope,
        networkMonitor = networkMonitor,
        retryBackoffMillis = retryBackoffMillis,
    )
}

/** [NetworkMonitor] whose online state is fixed for the lifetime of a test. */
private class FakeNetworkMonitor(
    private val online: Boolean,
) : com.calypsan.listenup.client.domain.repository.NetworkMonitor {
    override fun isOnline(): Boolean = online

    override val isOnlineFlow = kotlinx.coroutines.flow.MutableStateFlow(online)
    override val isOnUnmeteredNetworkFlow = kotlinx.coroutines.flow.MutableStateFlow(online)
}

/**
 * SSE client that NEVER reaches [ConnectionState.Connected] — `connect()` leaves the firehose
 * Disconnected. Models the outage the fix targets: the firehose is down, but the RPC/request
 * transport the outbox rides is healthy.
 */
private class NeverConnectingSseClient(
    private val state: SyncEngineState,
) : SseClient {
    private val flow = MutableSharedFlow<ParsedSseFrame>()
    override val frames: SharedFlow<ParsedSseFrame> = flow.asSharedFlow()

    override fun seedLastEventId(initial: Long?) = Unit

    override fun connect() {
        state.setConnection(ConnectionState.Disconnected(reason = "firehose down"))
    }

    override fun disconnect() {
        state.setConnection(ConnectionState.Disconnected(reason = "firehose down"))
    }

    override fun currentLastEventId(): Long? = null

    override suspend fun reseed(newLastEventId: Long?) = Unit

    override fun reconnectNow() = Unit
}

private object NoopCatchUp : CatchUp {
    override suspend fun <T : Any> catchUp(handler: SyncDomainHandler<T>): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun <T : Any> catchUpFromZero(handler: SyncDomainHandler<T>): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun catchUpAll(registry: ClientSyncDomainRegistry): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun <T : Any> catchUpTransient(handler: SyncDomainHandler<T>): AppResult<Set<String>> = AppResult.Success(emptySet())

    override suspend fun domains(): AppResult<List<String>> = AppResult.Success(emptyList())
}

private object NoopTagHandler : SyncDomainHandler<Tag> {
    override val domainName = "tags"
    override val payloadSerializer = Tag.serializer()

    override fun syncId(item: Tag): String = item.id

    override suspend fun onEvent(
        event: com.calypsan.listenup.api.sync.SyncEvent<Tag>,
        isOwnEcho: Boolean,
    ): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun onCatchUpItem(
        item: Tag,
        isTombstone: Boolean,
    ): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun localDigestRows(maxRevision: Long): List<Pair<String, Long>> = emptyList()
}

/**
 * DAO decorator that counts calls to [nextDispatchable] so a test can assert
 * how many drain waves the engine fired — each `runDrain()` invocation calls
 * `nextDispatchable()` exactly once.
 */
private class CountingDao(
    private val delegate: com.calypsan.listenup.client.data.local.db.PendingOperationV2Dao,
    private val dispatchCalls: AtomicInteger,
) : com.calypsan.listenup.client.data.local.db.PendingOperationV2Dao {
    override suspend fun insert(op: com.calypsan.listenup.client.data.local.db.PendingOperationV2Entity) = delegate.insert(op)

    override suspend fun get(clientOpId: String) = delegate.get(clientOpId)

    override suspend fun delete(clientOpId: String) = delegate.delete(clientOpId)

    override suspend fun update(op: com.calypsan.listenup.client.data.local.db.PendingOperationV2Entity) = delegate.update(op)

    override suspend fun nextDispatchable(maxAttempts: Int): List<com.calypsan.listenup.client.data.local.db.PendingOperationV2Entity> {
        dispatchCalls.incrementAndGet()
        return delegate.nextDispatchable(maxAttempts)
    }

    override suspend fun countDispatchable(maxAttempts: Int) = delegate.countDispatchable(maxAttempts)

    override suspend fun deleteQueuedOps(
        domainName: String,
        entityId: String,
        opType: String,
        maxAttempts: Int,
    ) = delegate.deleteQueuedOps(domainName, entityId, opType, maxAttempts)

    override fun observePending(maxAttempts: Int) = delegate.observePending(maxAttempts)

    override fun observeFailed(maxAttempts: Int) = delegate.observeFailed(maxAttempts)

    override suspend fun resetFailureCount(clientOpId: String) = delegate.resetFailureCount(clientOpId)

    override fun observeQueueDepth(maxAttempts: Int) = delegate.observeQueueDepth(maxAttempts)

    override fun observeDeadLetterCount(maxAttempts: Int) = delegate.observeDeadLetterCount(maxAttempts)

    override suspend fun deleteAllExcept(keepUserId: String) = delegate.deleteAllExcept(keepUserId)

    override suspend fun deleteAll() = delegate.deleteAll()

    override suspend fun gcDeadLetters(
        cutoffMillis: Long,
        maxAttempts: Int,
    ) = delegate.gcDeadLetters(cutoffMillis, maxAttempts)
}

/**
 * Fake SSE client that mirrors production's `SyncSseClient.connect()` semantics:
 * `connect()` transitions [state] to [ConnectionState.Connected], which is what
 * the engine's connection-up trigger listens for.
 */
private class FakeSseClient(
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
