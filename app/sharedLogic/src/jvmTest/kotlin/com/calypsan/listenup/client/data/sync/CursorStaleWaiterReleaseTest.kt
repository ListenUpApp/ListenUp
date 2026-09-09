package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.SyncFrame
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
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
 * Regression guard for a coalesced [SyncEngine.handleCursorStale] caller stranded by a FAILED
 * recovery pass.
 *
 * The leading loop snapshots the outstanding waiters (removing them from the shared list) BEFORE
 * each pass runs, and completed them only if the pass returned normally. When the pass threw — a
 * firehose `connect()` failure is enough — the outer cleanup's `drainWaiters()` could no longer see
 * the snapshot, so nobody completed or cancelled those waiters and the coalesced caller's `join()`
 * suspended for the life of the process. Both production callers join on that suspension: the
 * post-import listening-history refresh (app-scoped — a permanent hang) and the scan-progress
 * collector (the "Building your library" gate never lifts).
 *
 * This test drives exactly that interleaving on a confined dispatcher: a leading pass parked on a
 * gate, a coalesced caller registered behind it, then the follow-up pass whose `connect()` throws.
 * The coalesced caller must return regardless.
 */
class CursorStaleWaiterReleaseTest :
    FunSpec({

        test("a coalesced handleCursorStale caller is released when its covering recovery pass throws") {
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
                    // is still in flight.
                    val leadingPassStarted = CompletableDeferred<Unit>()
                    val releaseLeadingPass = CompletableDeferred<Unit>()

                    val catchUp =
                        ReleaseGatedCatchUp(
                            onPassStart = { passNumber ->
                                if (passNumber == 1) {
                                    leadingPassStarted.complete(Unit)
                                    releaseLeadingPass.await()
                                }
                            },
                        )

                    val state = SyncEngineState()
                    // Pass #1's connect() is call #1; the follow-up pass #2 (the one covering the
                    // coalesced caller) makes call #2 — which throws, failing that pass mid-flight.
                    val sse = FailingConnectSyncStreamClient(state, failOnConnectCall = 2)
                    val registry = ClientSyncDomainRegistry()
                    registry.register(ReleaseTagHandler())
                    val store = SyncCursorStore(db.syncCursorDao())
                    val engine = buildReleaseEngine(db, registry, store, state, catchUp, sse, scope)

                    withContext(confined) {
                        // Leading caller: enters handleCursorStale, runs catch-up pass #1, parks on the gate.
                        // runCatching so pass #2's throw does not surface as an unhandled exception on the
                        // scope — the leading caller is EXPECTED to fail here.
                        scope.launch { runCatching { engine.handleCursorStale() } }
                        withTimeout(5_000) { leadingPassStarted.await() }

                        // Coalesced caller: a recovery is already running, so it registers a waiter and
                        // suspends on it. Signal the instant it returns.
                        val coalescedCallerReturned = CompletableDeferred<Unit>()
                        scope.launch {
                            engine.handleCursorStale()
                            coalescedCallerReturned.complete(Unit)
                        }

                        // On the confined thread, yield enough for the coalesced caller to run through
                        // handleCursorStale's entry `withLock` (registering its waiter) and reach its
                        // `join()` — all while the leading pass is still parked on the gate.
                        repeat(10) { yield() }

                        // Release the leading pass: pass #1 returns, the loop sees the pending flag,
                        // snapshots the waiter, and runs pass #2 — whose connect() throws.
                        releaseLeadingPass.complete(Unit)

                        // The coalesced caller MUST return. Pre-fix its waiter was snapshotted out of the
                        // shared list and never completed or cancelled once the pass threw, so this await
                        // times out — the production hang, made visible.
                        withTimeout(5_000) { coalescedCallerReturned.await() }
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

private fun buildReleaseEngine(
    db: com.calypsan.listenup.client.data.local.db.ListenUpDatabase,
    registry: ClientSyncDomainRegistry,
    store: SyncCursorStore,
    state: SyncEngineState,
    catchUp: CatchUp,
    sse: SyncStreamClient,
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
            state = state,
            cursorAdvance = { domain, rev -> store.setCursor(domain, rev) },
        )
    return SyncEngine(
        registry = registry,
        queue = queue,
        state = state,
        store = store,
        catchUp = catchUp,
        syncStreamClient = sse,
        reconciler =
            SyncReconciler(
                registry = registry,
                store = store,
                // handleCursorStale never touches the reconciler, so every member of this fake
                // throws — an unexpected digest call names itself instead of passing silently.
                digestClient = DomainDigestClient(channel = RpcChannel.forTest(object : FakeSyncStreamService() {})),
                catchUp = catchUp,
            ),
        dispatcher = dispatcher,
        presenceRefreshSignal = PresenceRefreshSignal(),
        scope = scope,
    )
}

/**
 * [CatchUp] fake that gates each `catchUpAll` pass. [onPassStart] is invoked with the 1-based pass
 * number BEFORE the pass body (used to park the leading pass on a deferred).
 */
private class ReleaseGatedCatchUp(
    private val onPassStart: suspend (Int) -> Unit,
) : CatchUp {
    private val passCounter = AtomicInteger(0)

    override suspend fun catchUpAll(registry: ClientSyncDomainRegistry): AppResult<Unit> {
        onPassStart(passCounter.incrementAndGet())
        return AppResult.Success(Unit)
    }

    override suspend fun <T : Any> catchUp(handler: SyncDomainHandler<T>): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun <T : Any> catchUpFromZero(handler: SyncDomainHandler<T>): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun <T : Any> catchUpTransient(handler: SyncDomainHandler<T>): AppResult<Set<String>> = AppResult.Success(emptySet())

    override suspend fun domains(): AppResult<List<String>> = AppResult.Success(emptyList())
}

/** Minimal no-op tag handler so the registry is non-empty during catch-up. */
private class ReleaseTagHandler : SyncDomainHandler<Tag> {
    override val domainName = "tags"
    override val payloadSerializer: KSerializer<Tag> = Tag.serializer()

    override fun syncId(item: Tag): String = item.id

    override suspend fun onEvent(
        event: SyncEvent<Tag>,
    ): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun onCatchUpItem(
        item: Tag,
        isTombstone: Boolean,
    ): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun localDigestRows(maxRevision: Long): List<Pair<String, Long>> = emptyList()
}

/** Fake stream client whose `connect()` throws on the Nth call, so a recovery pass fails mid-flight. */
private class FailingConnectSyncStreamClient(
    private val state: SyncEngineState,
    private val failOnConnectCall: Int,
) : SyncStreamClient {
    private val flow = MutableSharedFlow<SyncFrame>()
    override val frames: SharedFlow<SyncFrame> = flow.asSharedFlow()

    private var seeded: Long? = null
    private var connectCalls = 0

    override fun seedLastEventId(initial: Long?) {
        seeded = initial
    }

    override fun connect() {
        connectCalls++
        if (connectCalls == failOnConnectCall) error("simulated firehose connect failure")
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
