package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.sync.SyncFrame
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * B4 proof: every reconcile/catch-up entry point in [SyncEngine] shares ONE catch-up single-flight,
 * so two DIFFERENT triggers — a scan-completion [SyncEngine.handleCursorStale] and a foreground
 * [SyncEngine.lifecycleReconcile] — can never drive `catchUpAll` concurrently.
 *
 * Before the shared guard each entry point had its OWN mutex ([SyncEngine.handleCursorStale]'s
 * `cursorStaleMutex`, [SyncEngine.lifecycleReconcile]'s `lifecycleReconcileMutex`), so both entered
 * `catchUpAll` at the same time — the overlapping-page-decode storm the cursorStale coalescer was
 * built to prevent, re-opened by the second entry point, and their interleaving `setCursor` writes.
 * Unguarded: maxConcurrent == 2 (RED). With the shared `catchUpMutex` the second trigger waits for
 * the first's pass to drain: maxConcurrent == 1 (GREEN).
 */
class CatchUpSingleFlightTest :
    FunSpec({

        test("handleCursorStale and lifecycleReconcile never run catchUpAll concurrently") {
            runBlocking {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val db = createInMemoryTestDatabase()
                try {
                    // A catch-up that parks in catchUpAll until released, recording peak concurrency.
                    val gate = CompletableDeferred<Unit>()
                    val inFlight = AtomicInteger(0)
                    val maxConcurrent = AtomicInteger(0)
                    val catchUp =
                        object : CatchUp {
                            override suspend fun <T : Any> catchUp(handler: SyncDomainHandler<T>) = AppResult.Success(Unit)

                            override suspend fun <T : Any> catchUpFromZero(handler: SyncDomainHandler<T>) = AppResult.Success(Unit)

                            override suspend fun catchUpAll(registry: ClientSyncDomainRegistry): AppResult<Unit> {
                                val now = inFlight.incrementAndGet()
                                maxConcurrent.updateAndGet { prev -> maxOf(prev, now) }
                                try {
                                    gate.await()
                                } finally {
                                    inFlight.decrementAndGet()
                                }
                                return AppResult.Success(Unit)
                            }

                            override suspend fun <T : Any> catchUpTransient(
                                handler: SyncDomainHandler<T>,
                            ) = AppResult.Success(emptySet<String>())

                            override suspend fun domains() = AppResult.Success(emptyList<String>())
                        }

                    val state = SyncEngineState()
                    val engine = buildSingleFlightEngine(db, state, catchUp, scope)

                    // Two DIFFERENT reconcile entry points race concurrently — the concrete B4 overlap:
                    // a scan-completion CursorStale recovery while a foreground lifecycle reconcile fires.
                    val cursorStale = scope.launch { engine.handleCursorStale() }
                    val lifecycle = scope.launch { engine.lifecycleReconcile(force = true) }

                    // Wait until at least one pass is parked in catchUpAll, then give the OTHER entry
                    // point a real chance to (wrongly) start its own concurrent catchUpAll.
                    withTimeout(5.seconds) {
                        while (inFlight.get() == 0) delay(5)
                    }
                    delay(300)

                    // The decisive invariant: the two entry points never overlap inside catchUpAll.
                    maxConcurrent.get() shouldBe 1

                    gate.complete(Unit)
                    withTimeout(5.seconds) {
                        cursorStale.join()
                        lifecycle.join()
                    }
                } finally {
                    scope.cancel()
                    db.close()
                }
            }
        }
    })

private fun buildSingleFlightEngine(
    db: ListenUpDatabase,
    state: SyncEngineState,
    catchUp: CatchUp,
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
            state = state,
            cursorAdvance = { domain, rev -> store.setCursor(domain, rev) },
        )
    return SyncEngine(
        registry = registry,
        queue = queue,
        state = state,
        store = store,
        catchUp = catchUp,
        sseClient = SingleFlightNoopSyncStreamClient(state),
        reconciler = noopSyncReconciler(registry, store, catchUp),
        dispatcher = dispatcher,
        presenceRefreshSignal = PresenceRefreshSignal(),
        scope = scope,
    )
}

/** Minimal [SyncStreamClient] whose connect/disconnect only flip [SyncEngineState] — no real transport. */
private class SingleFlightNoopSyncStreamClient(
    private val state: SyncEngineState,
) : SyncStreamClient {
    private val flow = MutableSharedFlow<SyncFrame>()
    override val frames: SharedFlow<SyncFrame> = flow.asSharedFlow()
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
        seeded = newLastEventId
    }

    override fun reconnectNow() = Unit
}
