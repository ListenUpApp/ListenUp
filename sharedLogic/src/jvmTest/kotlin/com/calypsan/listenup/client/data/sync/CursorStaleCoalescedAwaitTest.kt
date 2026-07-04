package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.KSerializer

/**
 * Regression guard for the post-import "Continue Listening empty until restart" bug.
 *
 * `SyncRepositoryImpl.refreshListeningHistory()` calls [SyncEngine.handleCursorStale] to force a
 * forward catch-up that drains the import's `FirehoseSuppressed` rows (written above the client's
 * cursor) into Room. But `handleCursorStale` COALESCES: if a recovery is already running (the common
 * case right after a large import, while `start()`'s own reconcile or a scan-completion recovery is
 * still draining), the second caller used to set `cursorStalePending = true` and return IMMEDIATELY
 * — before the follow-up pass that covers its request had run. So `refreshListeningHistory()` reported
 * "done" while the imported rows were still not in Room → Continue Listening + streak starved until a
 * restart.
 *
 * The fix makes a coalesced caller AWAIT a follow-up catch-up pass that runs at-or-after its request.
 * This test pins that: while a leading recovery is parked mid-`catchUpAll`, a second
 * `handleCursorStale()` call must not return until a *subsequent* `catchUpAll` (the follow-up pass it
 * triggered) has completed.
 */
class CursorStaleCoalescedAwaitTest :
    FunSpec({

        test("a coalesced handleCursorStale awaits the follow-up catch-up pass before returning") {
            // A single confined thread makes coroutine interleaving deterministic: each `yield()` is a
            // precise hand-off, so we can drive the leading caller to its gated park, then the coalesced
            // caller to its registration/await point, with no real-time races.
            val executor = Executors.newSingleThreadExecutor()
            val confined = executor.asCoroutineDispatcher()
            runBlocking {
                val scope = CoroutineScope(SupervisorJob() + confined)
                val db = createInMemoryTestDatabase()
                try {
                    // Gate the leading catch-up pass so a second handleCursorStale() coalesces while it
                    // is still in flight. `passesCompleted` counts catchUpAll passes that have fully
                    // returned — the signal for "a follow-up pass finished".
                    val leadingPassStarted = CompletableDeferred<Unit>()
                    val releaseLeadingPass = CompletableDeferred<Unit>()
                    val passesCompleted = AtomicInteger(0)

                    val catchUp =
                        GatedCatchUp(
                            onPassStart = { passNumber ->
                                if (passNumber == 1) {
                                    leadingPassStarted.complete(Unit)
                                    releaseLeadingPass.await()
                                }
                            },
                            onPassComplete = { passesCompleted.incrementAndGet() },
                        )

                    val state = SyncEngineState()
                    val sse = FakeCoalesceSseClient(state)
                    val registry = ClientSyncDomainRegistry()
                    registry.register(CoalesceTagHandler())
                    val store = SyncCursorStore(db.syncCursorDao())
                    val engine = buildCoalesceEngine(db, registry, store, state, catchUp, sse, scope)

                    withContext(confined) {
                        // Leading caller: enters handleCursorStale, runs catch-up pass #1, parks on the gate.
                        scope.launch { engine.handleCursorStale() }
                        withTimeout(5_000) { leadingPassStarted.await() }

                        // Second caller coalesces (a recovery is already running). Under the fix it must
                        // suspend until the follow-up pass it requests completes. Snapshot the completed-pass
                        // count at the instant it returns.
                        val passesCompletedWhenSecondReturned = CompletableDeferred<Int>()
                        scope.launch {
                            engine.handleCursorStale()
                            passesCompletedWhenSecondReturned.complete(passesCompleted.get())
                        }

                        // On the confined thread, yield enough for the coalesced caller to run through
                        // handleCursorStale's entry `withLock` (registering its pending request) and reach
                        // its suspension point — all while the leading pass is still parked on the gate, so
                        // the drain loop cannot yet observe the pending flag.
                        repeat(10) { yield() }

                        // Release the leading pass: pass #1 returns, the drain loop sees the coalesced
                        // caller's pending request and runs the follow-up pass #2.
                        releaseLeadingPass.complete(Unit)

                        val snapshot = withTimeout(5_000) { passesCompletedWhenSecondReturned.await() }

                        // The coalesced caller returned only after a follow-up pass completed: pass #1
                        // (leading) + pass #2 (the follow-up it requested) = at least 2 completed passes.
                        // Pre-fix the coalesced caller returned immediately after merely SETTING the pending
                        // flag — snapshot would be < 2 (the follow-up had not run yet).
                        snapshot shouldBeGreaterThanOrEqual 2
                    }
                } finally {
                    scope.cancel()
                    scope.coroutineContext.job.children
                        .forEach { it.join() }
                    db.close()
                    confined.close()
                    executor.shutdown()
                }
            }
        }
    })

private fun buildCoalesceEngine(
    db: com.calypsan.listenup.client.data.local.db.ListenUpDatabase,
    registry: ClientSyncDomainRegistry,
    store: SyncCursorStore,
    state: SyncEngineState,
    catchUp: CatchUp,
    sse: SseClient,
    scope: CoroutineScope,
): SyncEngine {
    val queue =
        PendingOperationQueue(
            dao = db.pendingOperationV2Dao(),
            sender = PendingOperationSender { AppResult.Failure(SyncError.NotFound(domain = "tags", entityId = "t1")) },
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
        catchUp = catchUp,
        sseClient = sse,
        reconciler =
            SyncReconciler(
                registry = registry,
                store = store,
                // handleCursorStale never touches the reconciler; providers are never invoked.
                digestClient =
                    DomainDigestClient(
                        httpClientProvider = { error("digest client unused in coalesce test") },
                        serverUrlProvider = { error("digest client unused in coalesce test") },
                    ),
                catchUp = catchUp,
            ),
        dispatcher = dispatcher,
        presenceRefreshSignal = PresenceRefreshSignal(),
        scope = scope,
    )
}

/**
 * [CatchUp] fake that gates each `catchUpAll` pass. [onPassStart] is invoked with the 1-based pass
 * number BEFORE the pass body (used to park the leading pass on a deferred); [onPassComplete] fires
 * after the pass body returns (used to count completed passes).
 */
private class GatedCatchUp(
    private val onPassStart: suspend (Int) -> Unit,
    private val onPassComplete: () -> Unit,
) : CatchUp {
    private val passCounter = AtomicInteger(0)

    override suspend fun catchUpAll(registry: ClientSyncDomainRegistry): AppResult<Unit> {
        val passNumber = passCounter.incrementAndGet()
        onPassStart(passNumber)
        onPassComplete()
        return AppResult.Success(Unit)
    }

    override suspend fun <T : Any> catchUp(handler: SyncDomainHandler<T>): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun <T : Any> catchUpFromZero(handler: SyncDomainHandler<T>): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun <T : Any> catchUpTransient(handler: SyncDomainHandler<T>): AppResult<Set<String>> = AppResult.Success(emptySet())

    override suspend fun domains(): AppResult<List<String>> = AppResult.Success(emptyList())
}

/** Minimal no-op tag handler so the registry is non-empty during catch-up. */
private class CoalesceTagHandler : SyncDomainHandler<Tag> {
    override val domainName = "tags"
    override val payloadSerializer: KSerializer<Tag> = Tag.serializer()

    override fun syncId(item: Tag): String = item.id

    override suspend fun onEvent(
        event: SyncEvent<Tag>,
        isOwnEcho: Boolean,
    ): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun onCatchUpItem(
        item: Tag,
        isTombstone: Boolean,
    ): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun localDigestRows(maxRevision: Long): List<Pair<String, Long>> = emptyList()
}

/** Fake SSE client mirroring production's `connect()`-flips-state semantics. */
private class FakeCoalesceSseClient(
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
