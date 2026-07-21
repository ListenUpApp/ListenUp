package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.sync.SyncFrame
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
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

        // M1 regression: a forced trigger that arrives WHILE a pass is in flight must not be lost to
        // the coalescing — the leader has to run one covering follow-up pass for it. Before the
        // ownership-scoped finally fix, a returning leader could stomp the pending flag and silently
        // drop exactly this forced follow-up.
        test("a forced trigger arriving mid-pass schedules a covering follow-up pass") {
            val catchUp = GatingLifecycleCatchUp()
            // Large interval so the follow-up is earned by force alone, never by the debounce window.
            withLifecycleEngine(minIntervalMs = 60_000L, catchUp = catchUp) { engine, _, _ ->
                coroutineScope {
                    val leader = launch { engine.lifecycleReconcile() }
                    // Wait until the leader is provably inside pass 1 (running flag already set).
                    catchUp.firstPassStarted.await()
                    // Forced trigger during the in-flight pass → must register a covering follow-up.
                    engine.lifecycleReconcile(force = true)
                    // Let pass 1 finish; the leader's loop must now honor the pending follow-up.
                    catchUp.releaseFirstPass.complete(Unit)
                    leader.join()
                }
                catchUp.catchUpAllInvocations.get() shouldBe 2
            }
        }
    })

/** Recording [CatchUp] that counts forward `catchUpAll` passes — the lifecycle-reconcile signal. */
private open class RecordingLifecycleCatchUp : CatchUp {
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

/**
 * Recording [CatchUp] that blocks its FIRST `catchUpAll` pass until released, so a test can inject a
 * concurrent trigger while a pass is provably in flight. [firstPassStarted] fires once the leader is
 * inside pass 1 (running flag set); [releaseFirstPass] lets that pass complete.
 */
private class GatingLifecycleCatchUp : RecordingLifecycleCatchUp() {
    val firstPassStarted = CompletableDeferred<Unit>()
    val releaseFirstPass = CompletableDeferred<Unit>()

    override suspend fun catchUpAll(registry: ClientSyncDomainRegistry): AppResult<Unit> {
        val result = super.catchUpAll(registry)
        if (catchUpAllInvocations.get() == 1) {
            firstPassStarted.complete(Unit)
            releaseFirstPass.await()
        }
        return result
    }
}

private fun withLifecycleEngine(
    minIntervalMs: Long,
    catchUp: RecordingLifecycleCatchUp = RecordingLifecycleCatchUp(),
    block: suspend (SyncEngine, RecordingLifecycleCatchUp, FakeLifecycleSse) -> Unit,
) = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = createInMemoryTestDatabase()
    try {
        val engineAndCatchUp = buildLifecycleEngine(db, scope, minIntervalMs, catchUp)
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
    catchUp: RecordingLifecycleCatchUp = RecordingLifecycleCatchUp(),
): Triple<SyncEngine, RecordingLifecycleCatchUp, FakeLifecycleSse> {
    val registry = ClientSyncDomainRegistry()
    registry.register(LifecycleNoopTagHandler)
    val store = SyncCursorStore(db.syncCursorDao())
    val state = SyncEngineState()
    val sse = FakeLifecycleSse(state)
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
        disconnect()
        seeded = newLastEventId
    }

    override fun reconnectNow() = Unit
}
