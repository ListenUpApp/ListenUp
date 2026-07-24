package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.sync.SyncFrame
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.api.result.AppResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * Pins the fix for the onboarding catch-up ↔ CursorStale spin.
 *
 * During initial population the server emits `Completed` before it has finished writing all book
 * revisions, so the client reconciles a still-moving server: each reconnect lands below the
 * (DROP_OLDEST) replay-bus floor and the server replies `CursorStale`, which the dispatcher routes
 * straight back into [SyncEngine.handleCursorStale]. With no guard, every such signal — plus the
 * fire-and-forget scan-completion reconcile — starts ANOTHER full `catchUpAll`, so many re-decode
 * passes run concurrently: the runaway large-object GC and the render-starved (never-clearing)
 * scan overlay.
 *
 * Guarded + coalescing, at most one recovery runs at a time and a burst of triggers collapses into
 * a single follow-up pass.
 */
class CursorStaleReentrancyTest :
    FunSpec({

        test("concurrent CursorStale recoveries never run catchUpAll concurrently and coalesce") {
            runBlocking {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val db = createInMemoryTestDatabase()
                try {
                    // A catch-up that parks until released, recording concurrency + total calls.
                    val gate = CompletableDeferred<Unit>()
                    val inFlight = AtomicInteger(0)
                    val maxConcurrent = AtomicInteger(0)
                    val totalCalls = AtomicInteger(0)
                    val catchUp =
                        object : CatchUp {
                            override suspend fun <T : Any> catchUp(handler: SyncDomainHandler<T>) = AppResult.Success(Unit)

                            override suspend fun <T : Any> catchUpFromZero(handler: SyncDomainHandler<T>) = AppResult.Success(Unit)

                            override suspend fun catchUpAll(registry: ClientSyncDomainRegistry): AppResult<Unit> {
                                totalCalls.incrementAndGet()
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
                    val engine = buildEngineWithCatchUp(db, state, catchUp, scope)

                    // Three CursorStale recoveries race in while catch-up is parked (in flight).
                    val jobs = (1..3).map { scope.launch { engine.handleCursorStale() } }

                    withTimeout(5.seconds) {
                        while (totalCalls.get() == 0) delay(5)
                    }
                    // Give the other two a real chance to (wrongly) start their own catch-up.
                    delay(300)

                    // The decisive invariant: catchUpAll is never re-entered concurrently.
                    maxConcurrent.get() shouldBe 1

                    gate.complete(Unit)
                    withTimeout(5.seconds) { jobs.forEach { it.join() } }

                    // Coalesced: at most one in-flight recovery + one queued follow-up — not one per trigger.
                    (totalCalls.get() <= 2) shouldBe true
                } finally {
                    scope.cancel()
                    db.close()
                }
            }
        }
    })

private fun buildEngineWithCatchUp(
    db: com.calypsan.listenup.client.data.local.db.ListenUpDatabase,
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
        syncStreamClient = NoopSyncStreamClient(state),
        reconciler = noopSyncReconciler(registry, store, catchUp),
        dispatcher = dispatcher,
        presenceRefreshSignal = PresenceRefreshSignal(),
        scope = scope,
    )
}

/** Minimal [SyncStreamClient] whose connect/disconnect only flip [SyncEngineState] — no real transport. */
private class NoopSyncStreamClient(
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
