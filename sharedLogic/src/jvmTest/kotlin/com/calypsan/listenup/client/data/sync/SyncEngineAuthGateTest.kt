package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.sync.domains.OpKind
import com.calypsan.listenup.client.data.sync.domains.OutboxChannel
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.serializer

private val testChannel = OutboxChannel("tags", String.serializer(), setOf(OpKind.Upsert))

/** CatchUp that counts pass-level invocations (start + forced lifecycle reconcile). */
private class GateCountingCatchUp : CatchUp {
    val catchUpAllCalls = AtomicInteger(0)

    override suspend fun <T : Any> catchUp(handler: SyncDomainHandler<T>): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun <T : Any> catchUpFromZero(handler: SyncDomainHandler<T>): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun catchUpAll(registry: ClientSyncDomainRegistry): AppResult<Unit> {
        catchUpAllCalls.incrementAndGet()
        return AppResult.Success(Unit)
    }

    override suspend fun <T : Any> catchUpTransient(handler: SyncDomainHandler<T>): AppResult<Set<String>> = AppResult.Success(emptySet())

    override suspend fun domains(): AppResult<List<String>> = AppResult.Success(emptyList())
}

/** SseClient recording connect/disconnect so the gate's park/resume is observable across threads. */
private class GateFakeSse : SseClient {
    private val flow = MutableSharedFlow<ParsedSseFrame>()
    override val frames: SharedFlow<ParsedSseFrame> = flow.asSharedFlow()

    @Volatile
    var connected = false

    override fun seedLastEventId(initial: Long?) = Unit

    override fun connect() {
        connected = true
    }

    override fun disconnect() {
        connected = false
    }

    override fun currentLastEventId(): Long? = null

    override suspend fun reseed(newLastEventId: Long?) {
        disconnect()
    }

    override fun reconnectNow() = Unit
}

class SyncEngineAuthGateTest :
    FunSpec({
        // The engine's auth gate reacts to authState via a long-lived background collector. Virtual
        // time (runTest + advanceUntilIdle) does not reliably deliver post-start StateFlow emissions
        // to these engine collectors, so — like SyncEngineDiscoverRefreshTest — this drives a real
        // Dispatchers.Default scope and polls with withTimeout.
        test("SessionLapsed parks the firehose and gates the outbox; re-auth resumes with a forced reconcile") {
            runBlocking {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val db = createInMemoryTestDatabase()
                try {
                    val registry = ClientSyncDomainRegistry()
                    val store = SyncCursorStore(db.syncCursorDao())
                    val sendCount = AtomicInteger(0)
                    val queue =
                        PendingOperationQueue(
                            dao = db.pendingOperationV2Dao(),
                            sender =
                                PendingOperationSender {
                                    sendCount.incrementAndGet()
                                    AppResult.Success(Unit)
                                },
                        )
                    val state = SyncEngineState()
                    val fakeSse = GateFakeSse()
                    val catchUp = GateCountingCatchUp()
                    val authState =
                        MutableStateFlow<AuthState>(AuthState.Authenticated(UserId("u1"), SessionId("s1")))
                    val engine =
                        SyncEngine(
                            registry = registry,
                            queue = queue,
                            state = state,
                            store = store,
                            catchUp = catchUp,
                            sseClient = fakeSse,
                            reconciler = noopSyncReconciler(registry, store, catchUp),
                            dispatcher =
                                SyncEventDispatcher(
                                    registry = registry,
                                    queue = queue,
                                    state = state,
                                    cursorAdvance = { domain, rev -> store.setCursor(domain, rev) },
                                ),
                            presenceRefreshSignal = PresenceRefreshSignal(),
                            scope = scope,
                            authState = authState,
                        )

                    engine.start(currentUserId = "u1")
                    fakeSse.connected shouldBe true
                    val callsAfterStart = catchUp.catchUpAllCalls.get()
                    // Let the gate settle on the initial Authenticated value before flipping it.
                    delay(150)

                    // Lapse: the gate parks the firehose entirely (spec §6.5).
                    authState.value = AuthState.SessionLapsed(UserId("u1"))
                    withTimeout(5_000L) { while (fakeSse.connected) delay(10) }
                    fakeSse.connected shouldBe false

                    // Outbox gate: an enqueue while lapsed burns no retry budget (spec §6.5).
                    queue.enqueue(testChannel, "t1", OpKind.Upsert, "{}", "u1")
                    delay(150)
                    sendCount.get() shouldBe 0

                    // Re-auth: connect + forced lifecycleReconcile on the SessionLapsed→Authenticated edge.
                    authState.value = AuthState.Authenticated(UserId("u1"), SessionId("s2"))
                    withTimeout(5_000L) {
                        while (!fakeSse.connected || catchUp.catchUpAllCalls.get() < callsAfterStart + 1) delay(10)
                    }
                    fakeSse.connected shouldBe true
                    catchUp.catchUpAllCalls.get() shouldBeGreaterThanOrEqual callsAfterStart + 1

                    engine.stopAndJoin()
                } finally {
                    scope.cancel()
                    scope.coroutineContext.job.children
                        .forEach { it.join() }
                    db.close()
                }
            }
        }
    })
