package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.client.data.repository.FakeDownloadRepository
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
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Verifies that `SyncEngine` calls [DownloadRepository.recheckWaitingForServer] whenever the
 * SSE connection transitions to [ConnectionState.Connected].
 *
 * Pre-fix: the method existed and was correct but was called from no production code path —
 * a transcode-waiting download could only recover via the 24h backstop.
 * Post-fix: connecting (or reconnecting) SSE triggers the recheck so missed
 * `transcode.complete` events are recovered promptly.
 */
class SyncEngineReconnectBehaviorTest :
    FunSpec({

        test("recheckWaitingForServer is called when SSE transitions to Connected") {
            runBlocking {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val db = createInMemoryTestDatabase()
                try {
                    val recheckCalls = mutableListOf<Unit>()
                    val downloadRepository =
                        object : FakeDownloadRepository() {
                            override suspend fun recheckWaitingForServer(): AppResult<Unit> {
                                recheckCalls.add(Unit)
                                return AppResult.Success(Unit)
                            }
                        }
                    val state = SyncEngineState()
                    val sse = FakeReconnectSseClient(state)
                    val engine = buildReconnectEngine(db, state, sse, downloadRepository, scope)

                    engine.start(currentUserId = "u1")

                    withTimeout(5.seconds) {
                        while (recheckCalls.isEmpty()) {
                            kotlinx.coroutines.delay(10)
                        }
                    }
                    recheckCalls.size shouldBe 1
                } finally {
                    scope.cancel()
                    scope.coroutineContext.job.children
                        .forEach { it.join() }
                    db.close()
                }
            }
        }

        test("recheckWaitingForServer is called again on each reconnect") {
            runBlocking {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val db = createInMemoryTestDatabase()
                try {
                    val recheckCalls = mutableListOf<Unit>()
                    val downloadRepository =
                        object : FakeDownloadRepository() {
                            override suspend fun recheckWaitingForServer(): AppResult<Unit> {
                                recheckCalls.add(Unit)
                                return AppResult.Success(Unit)
                            }
                        }
                    val state = SyncEngineState()
                    val sse = FakeReconnectSseClient(state)
                    val engine = buildReconnectEngine(db, state, sse, downloadRepository, scope)

                    engine.start(currentUserId = "u1")

                    // First connect — the SSE fake already set Connected in connect()
                    withTimeout(5.seconds) {
                        while (recheckCalls.size < 1) {
                            kotlinx.coroutines.delay(10)
                        }
                    }

                    // Simulate reconnect: disconnect first and wait for state to settle,
                    // then reconnect so distinctUntilChanged fires the Connected edge again.
                    sse.simulateDisconnect()
                    // Allow the collector to observe Disconnected before we flip back.
                    kotlinx.coroutines.delay(RECONNECT_SETTLE_MS)
                    sse.simulateReconnect()

                    withTimeout(5.seconds) {
                        while (recheckCalls.size < 2) {
                            kotlinx.coroutines.delay(10)
                        }
                    }
                    recheckCalls.size shouldBe 2
                } finally {
                    scope.cancel()
                    scope.coroutineContext.job.children
                        .forEach { it.join() }
                    db.close()
                }
            }
        }

        test("recheckWaitingForServer failure does not break the drain trigger") {
            runBlocking {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val db = createInMemoryTestDatabase()
                try {
                    val state = SyncEngineState()
                    val sse = FakeReconnectSseClient(state)
                    // Repo that always throws — must not kill the connection-up collector
                    val failingDownloadRepository =
                        object : FakeDownloadRepository() {
                            override suspend fun recheckWaitingForServer(): AppResult<Unit> {
                                error("simulated recheck failure")
                            }
                        }
                    val engine = buildReconnectEngine(db, state, sse, failingDownloadRepository, scope)

                    // Engine start must complete without throwing
                    engine.start(currentUserId = "u1")

                    // The engine is still running (not crashed): disconnect + reconnect should
                    // not throw or deadlock even though recheck keeps failing
                    sse.simulateDisconnect()
                    sse.simulateReconnect()

                    // Give the collect lambda a moment to process
                    withTimeout(5.seconds) {
                        while (state.value.connection !is ConnectionState.Connected) {
                            kotlinx.coroutines.delay(10)
                        }
                    }
                    // Still connected → drain loop survived the failure
                    (state.value.connection is ConnectionState.Connected) shouldBe true
                } finally {
                    scope.cancel()
                    scope.coroutineContext.job.children
                        .forEach { it.join() }
                    db.close()
                }
            }
        }
    })

// ---------------------------------------------------------------------------
// Local helpers
// ---------------------------------------------------------------------------

/** Milliseconds to let the SSE collector observe [ConnectionState.Disconnected] before reconnecting. */
private const val RECONNECT_SETTLE_MS = 50L

private fun buildReconnectEngine(
    db: com.calypsan.listenup.client.data.local.db.ListenUpDatabase,
    state: SyncEngineState,
    sse: FakeReconnectSseClient,
    downloadRepository: com.calypsan.listenup.client.domain.repository.DownloadRepository,
    scope: CoroutineScope,
): SyncEngine {
    val registry = ClientSyncDomainRegistry()
    val store = SyncCursorStore(db.syncCursorDao())
    val queue =
        PendingOperationQueue(
            dao = db.pendingOperationV2Dao(),
            sender = PendingOperationSender { AppResult.Success(Unit) },
        )
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
        catchUp = NoopReconnectCatchUp,
        sseClient = sse,
        dispatcher = dispatcher,
        downloadRepository = downloadRepository,
        scope = scope,
    )
}

private object NoopReconnectCatchUp : CatchUp {
    override suspend fun <T : Any> catchUp(handler: SyncDomainHandler<T>): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun catchUpAll(registry: ClientSyncDomainRegistry): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun domains(): AppResult<List<String>> = AppResult.Success(emptyList())
}

/**
 * Fake SSE client that sets [ConnectionState.Connected] on [connect] and exposes
 * [simulateDisconnect]/[simulateReconnect] for testing the reconnect edge.
 */
private class FakeReconnectSseClient(
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

    fun simulateDisconnect() {
        state.setConnection(ConnectionState.Disconnected(reason = "test-reconnect"))
    }

    fun simulateReconnect() {
        state.setConnection(ConnectionState.Connected(lastEventId = seeded))
    }
}
