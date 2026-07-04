package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.atomic.AtomicInteger
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
 * Verifies [SyncEngine.lifecycleReconcile] — the centralized recovery pass every lifecycle edge
 * (app-foreground, firehose reconnect) funnels into.
 *
 * Contract:
 *  1. It runs a FORWARD catch-up (`catchUpAll`) — the piece the digest-only [SyncEngine.forceReconcile]
 *     lacks, and the whole reason a `FirehoseSuppressed` above-cursor gap self-heals.
 *  2. It runs even when the engine is already started (unlike `start()`, which no-ops for the same
 *     user) — killing the "foreground does zero reconciliation" hole.
 *  3. It is debounced: a non-forced pass within the min interval of the last is skipped.
 *  4. `force = true` bypasses the debounce.
 *  5. Concurrent triggers coalesce into a single pass.
 */
class SyncEngineLifecycleReconcileTest :
    FunSpec({

        test("lifecycleReconcile runs a forward catch-up") {
            withLifecycleEngine(minIntervalMs = 0L) { engine, catchUp, _ ->
                engine.lifecycleReconcile()
                catchUp.catchUpAllInvocations.get() shouldBe 1
            }
        }

        test("lifecycleReconcile runs even when the engine is already started (no foreground no-op hole)") {
            withLifecycleEngine(minIntervalMs = 0L) { engine, catchUp, sse ->
                engine.start(currentUserId = "u1")
                // start() ran its own catch-up once; a same-user re-start would no-op.
                catchUp.catchUpAllInvocations.get() shouldBe 1

                // The foreground edge on an already-started engine still reconciles.
                engine.lifecycleReconcile()
                catchUp.catchUpAllInvocations.get() shouldBe 2

                engine.stopAndJoin()
            }
        }

        test("a second lifecycleReconcile within the debounce window is skipped") {
            // A large interval means the second call is always inside the window.
            withLifecycleEngine(minIntervalMs = 60_000L) { engine, catchUp, _ ->
                engine.lifecycleReconcile()
                engine.lifecycleReconcile()
                catchUp.catchUpAllInvocations.get() shouldBe 1
            }
        }

        test("force = true bypasses the debounce window") {
            withLifecycleEngine(minIntervalMs = 60_000L) { engine, catchUp, _ ->
                engine.lifecycleReconcile()
                engine.lifecycleReconcile(force = true)
                catchUp.catchUpAllInvocations.get() shouldBe 2
            }
        }

        test("concurrent lifecycleReconcile triggers coalesce into one pass") {
            withLifecycleEngine(minIntervalMs = 60_000L) { engine, catchUp, _ ->
                coroutineScope {
                    repeat(8) { launch { engine.lifecycleReconcile() } }
                }
                catchUp.catchUpAllInvocations.get() shouldBe 1
            }
        }
    })

/** Recording [CatchUp] that counts forward `catchUpAll` passes — the lifecycle-reconcile signal. */
private class RecordingLifecycleCatchUp : CatchUp {
    val catchUpAllInvocations = AtomicInteger(0)

    override suspend fun <T : Any> catchUp(handler: SyncDomainHandler<T>): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun <T : Any> catchUpFromZero(handler: SyncDomainHandler<T>): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun catchUpAll(registry: ClientSyncDomainRegistry): AppResult<Unit> {
        catchUpAllInvocations.incrementAndGet()
        return AppResult.Success(Unit)
    }

    override suspend fun <T : Any> catchUpTransient(handler: SyncDomainHandler<T>): AppResult<Set<String>> = AppResult.Success(emptySet())

    override suspend fun domains(): AppResult<List<String>> = AppResult.Success(emptyList())
}

private fun withLifecycleEngine(
    minIntervalMs: Long,
    block: suspend (SyncEngine, RecordingLifecycleCatchUp, FakeLifecycleSse) -> Unit,
) = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = createInMemoryTestDatabase()
    try {
        val engineAndCatchUp = buildLifecycleEngine(db, scope, minIntervalMs)
        block(engineAndCatchUp.first, engineAndCatchUp.second, engineAndCatchUp.third)
    } finally {
        scope.cancel()
        scope.coroutineContext.job.children
            .forEach { it.join() }
        db.close()
    }
}

private fun buildLifecycleEngine(
    db: ListenUpDatabase,
    scope: CoroutineScope,
    minIntervalMs: Long,
): Triple<SyncEngine, RecordingLifecycleCatchUp, FakeLifecycleSse> {
    val registry = ClientSyncDomainRegistry()
    registry.register(LifecycleNoopTagHandler)
    val store = SyncCursorStore(db.syncCursorDao())
    val state = SyncEngineState()
    val sse = FakeLifecycleSse(state)
    val catchUp = RecordingLifecycleCatchUp()
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
    val engine =
        SyncEngine(
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
            lifecycleReconcileMinIntervalMs = minIntervalMs,
        )
    return Triple(engine, catchUp, sse)
}

private object LifecycleNoopTagHandler : SyncDomainHandler<Tag> {
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

/** Fake SSE that flips [SyncEngineState] on connect/disconnect, mirroring production semantics. */
private class FakeLifecycleSse(
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
