package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Verifies `SyncEngine.start()` is idempotent under re-entry.
 *
 * Pre-fix: `MainActivity.onResume` calls `connectRealtime()` → `engine.start()` on
 * every resume. Two quick resumes race two concurrent `catchUpAll` invocations
 * with duplicate `onCatchUpItem` calls and cursor-advance races. The "should I
 * re-fire" logic lives inside `start()` so callers don't need to guard.
 *
 * Contract:
 *  1. Concurrent calls for the same user run catch-up exactly once.
 *  2. A sequential second call for the same user is a no-op (no second catch-up).
 *  3. A call with a different user cancels the prior engine and rebuilds — two
 *     catch-up invocations, the new user is the live one.
 *  4. A call after a previous failed runStart() retries — not a silent no-op,
 *     so the documented recovery path (forceFullResync) actually works.
 */
class SyncEngineStartIdempotencyTest :
    FunSpec({

        test("two concurrent start() calls with same user don't run two catch-up loops") {
            runBlocking {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val db = createInMemoryTestDatabase()
                try {
                    val catchUp = CountingCatchUp()
                    val state = SyncEngineState()
                    val sse = FakeIdempotencySseClient(state)
                    val engine = buildEngine(db, state, catchUp, sse, scope)

                    coroutineScope {
                        launch { engine.start(currentUserId = "user-a") }
                        launch { engine.start(currentUserId = "user-a") }
                    }

                    catchUp.invocations.get() shouldBe 1
                } finally {
                    scope.cancel()
                    scope.coroutineContext.job.children
                        .forEach { it.join() }
                    db.close()
                }
            }
        }

        test("start() after a previous start() with the same user is a no-op (no second catch-up)") {
            runBlocking {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val db = createInMemoryTestDatabase()
                try {
                    val catchUp = CountingCatchUp()
                    val state = SyncEngineState()
                    val sse = FakeIdempotencySseClient(state)
                    val engine = buildEngine(db, state, catchUp, sse, scope)

                    engine.start(currentUserId = "user-a")
                    engine.start(currentUserId = "user-a")

                    catchUp.invocations.get() shouldBe 1
                } finally {
                    scope.cancel()
                    scope.coroutineContext.job.children
                        .forEach { it.join() }
                    db.close()
                }
            }
        }

        test("start() with a different user cancels prior and restarts (two catch-ups)") {
            runBlocking {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val db = createInMemoryTestDatabase()
                try {
                    val catchUp = CountingCatchUp()
                    val state = SyncEngineState()
                    val sse = FakeIdempotencySseClient(state)
                    val engine = buildEngine(db, state, catchUp, sse, scope)

                    engine.start(currentUserId = "user-a")
                    engine.start(currentUserId = "user-b")

                    catchUp.invocations.get() shouldBe 2
                    // queue.clearForUserChange("user-b") would have wiped any user-a ops.
                    // Enqueue under user-a, then start under user-b — the engine's
                    // second start should clear user-a's queued op.
                } finally {
                    scope.cancel()
                    scope.coroutineContext.job.children
                        .forEach { it.join() }
                    db.close()
                }
            }
        }

        test("start() after a failed runStart() retries — not a silent no-op forever") {
            // Regression guard for the same-user no-op recovery gap:
            // if the prior runStart() threw, engineJob completed exceptionally
            // and currentUserStarted is false, but currentUser is still set.
            // Without the `currentUserStarted` clause in start()'s no-op check,
            // every subsequent start("user-a") would be a silent no-op forever,
            // stranding SyncRepositoryImpl.forceFullResync(). With the guard,
            // the second start retries.
            runBlocking {
                // Swallow the simulated catch-up failure at the scope level so
                // it doesn't surface as an "uncaught exception before next test"
                // via the JVM's default handler. The test only cares about the
                // retry behavior; the exception itself is intentional.
                val swallow =
                    CoroutineExceptionHandler { _, _ ->
                        // expected — simulated failure
                    }
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + swallow)
                val db = createInMemoryTestDatabase()
                try {
                    val catchUp = FailingThenSucceedingCatchUp()
                    val state = SyncEngineState()
                    val sse = FakeIdempotencySseClient(state)
                    val engine = buildEngine(db, state, catchUp, sse, scope)

                    // First start: runStart throws inside the launched job.
                    // engineJob completes exceptionally; start() itself returns
                    // normally because job.join() doesn't rethrow.
                    engine.start(currentUserId = "user-a")
                    catchUp.invocations.get() shouldBe 1

                    // Second start for the same user: must retry, not no-op.
                    engine.start(currentUserId = "user-a")
                    catchUp.invocations.get() shouldBe 2
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
    state: SyncEngineState,
    catchUp: CatchUp,
    sse: SseClient,
    scope: CoroutineScope,
): SyncEngine {
    val registry = ClientSyncDomainRegistry()
    registry.register(IdempotencyNoopTagHandler)
    val store = SyncCursorStore(db.syncCursorDao())
    val queue =
        PendingOperationQueue(
            dao = db.pendingOperationV2Dao(),
            sender =
                PendingOperationSender {
                    // Non-retryable failure keeps queue activity minimal and avoids
                    // race noise during start() — the idempotency contract is what
                    // we're isolating, not drain wiring.
                    AppResult.Failure(SyncError.NotFound(domain = "tags", entityId = "t1"))
                },
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
        sseClient = sse,
        reconciler = noopSyncReconciler(registry, store, catchUp),
        dispatcher = dispatcher,
        presenceRefreshSignal = PresenceRefreshSignal(),
        scope = scope,
    )
}

/**
 * Recording fake that counts `catchUpAll` invocations — the canonical signal
 * for "did start() run a catch-up loop?"
 */
private class CountingCatchUp : CatchUp {
    val invocations = AtomicInteger(0)

    override suspend fun <T : Any> catchUp(handler: SyncDomainHandler<T>): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun <T : Any> catchUpFromZero(handler: SyncDomainHandler<T>): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun catchUpAll(registry: ClientSyncDomainRegistry): AppResult<Unit> {
        invocations.incrementAndGet()
        return AppResult.Success(Unit)
    }

    override suspend fun <T : Any> catchUpTransient(handler: SyncDomainHandler<T>): AppResult<Set<String>> = AppResult.Success(emptySet())

    override suspend fun domains(): AppResult<List<String>> = AppResult.Success(emptyList())
}

/**
 * Fake that throws on its first `catchUpAll` invocation and succeeds afterwards.
 * Drives the "engineJob completes exceptionally → retry must work" path —
 * runStart() doesn't catch, so the throw propagates out of the launched job
 * and into engineJob's failure state.
 */
private class FailingThenSucceedingCatchUp : CatchUp {
    val invocations = AtomicInteger(0)

    override suspend fun <T : Any> catchUp(handler: SyncDomainHandler<T>): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun <T : Any> catchUpFromZero(handler: SyncDomainHandler<T>): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun catchUpAll(registry: ClientSyncDomainRegistry): AppResult<Unit> {
        val attempt = invocations.incrementAndGet()
        if (attempt == 1) {
            error("simulated catch-up failure on first attempt")
        }
        return AppResult.Success(Unit)
    }

    override suspend fun <T : Any> catchUpTransient(handler: SyncDomainHandler<T>): AppResult<Set<String>> = AppResult.Success(emptySet())

    override suspend fun domains(): AppResult<List<String>> = AppResult.Success(emptyList())
}

private object IdempotencyNoopTagHandler : SyncDomainHandler<Tag> {
    override val domainName = "tags"
    override val payloadSerializer = Tag.serializer()

    override fun syncId(item: Tag): String = item.id

    override suspend fun onEvent(
        event: com.calypsan.listenup.api.sync.SyncEvent<Tag>,
    ): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun onCatchUpItem(
        item: Tag,
        isTombstone: Boolean,
    ): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun localDigestRows(maxRevision: Long): List<Pair<String, Long>> = emptyList()
}

/**
 * Fake SSE client mirroring production's `connect()`-flips-state semantics.
 * Local to this test so fakes don't leak between files.
 */
private class FakeIdempotencySseClient(
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
