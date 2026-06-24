package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class SyncEngineDiscoverRefreshTest :
    FunSpec({

        test("start primes the activity feed once, after catch-up") {
            runTest {
                val primeCount = AtomicInteger(0)
                val db = createInMemoryTestDatabase(StandardTestDispatcher(testScheduler))
                val registry = ClientSyncDomainRegistry()
                val store = SyncCursorStore(db.syncCursorDao())
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = PendingOperationSender { AppResult.Success(Unit) },
                    )
                val state = SyncEngineState()
                val catchUp = NoopRefreshCatchUp()
                val dispatcher =
                    SyncEventDispatcher(
                        registry = registry,
                        queue = queue,
                        state = state,
                        cursorAdvance = { domain, rev -> store.setCursor(domain, rev) },
                    )
                val engine =
                    SyncEngine(
                        registry = registry,
                        queue = queue,
                        state = state,
                        store = store,
                        catchUp = catchUp,
                        sseClient = FlippingFakeSse(state),
                        reconciler = noopSyncReconciler(registry, store, catchUp),
                        dispatcher = dispatcher,
                        presenceRefreshSignal = PresenceRefreshSignal(),
                        activityRefreshSignal = ActivityRefreshSignal(),
                        scope = backgroundScope,
                        primeActivityFeed = { primeCount.incrementAndGet() },
                    )

                engine.start(currentUserId = "u1")

                primeCount.get() shouldBe 1

                engine.stopAndJoin()
                db.close()
            }
        }
    })

/** No-op catch-up that flips no state — start completes without doing per-domain work. */
private class NoopRefreshCatchUp : CatchUp {
    override suspend fun <T : Any> catchUp(handler: SyncDomainHandler<T>): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun <T : Any> catchUpFromZero(handler: SyncDomainHandler<T>): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun catchUpAll(registry: ClientSyncDomainRegistry): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun <T : Any> catchUpTransient(handler: SyncDomainHandler<T>): AppResult<Set<String>> = AppResult.Success(emptySet())

    override suspend fun domains(): AppResult<List<String>> = AppResult.Success(emptyList())
}

/** Fake SSE that flips [SyncEngineState] on connect/disconnect, mirroring production semantics. */
private class FlippingFakeSse(
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
