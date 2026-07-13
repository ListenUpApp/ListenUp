package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.client.data.sync.domains.OpKind
import com.calypsan.listenup.client.data.sync.domains.OutboxChannel
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.serializer

// "tags" IS registered as a mirrored sync handler below, so it is a stand-in for any mirrored
// outbox domain (books/series/contributors). "preferences" is a real client-only channel with no
// sync handler — the negative case.
private val reconcileTagsChannel = OutboxChannel("tags", String.serializer(), setOf(OpKind.Upsert), idempotent = true)
private val reconcilePreferencesChannel =
    OutboxChannel("preferences", String.serializer(), setOf(OpKind.Update), idempotent = true)

private const val TIMEOUT_SECONDS = 5L
private const val QUIET_WINDOW_MILLIS = 400L

/**
 * Reconcile-on-drain-success: after an outbox op SENDS (drain deletes it), the engine issues one
 * targeted [CatchUp.fetchTransient] for that entity so a server echo the in-flight anti-flicker
 * shield dropped lands promptly — closing the "incomplete optimistic write stays stale until the
 * next digest" gap.
 *
 * RED (pre-fix): the engine never fetched after a drain, so the entity stayed stale until a
 * lifecycle digest. GREEN: exactly one targeted `?ids=` fetch fires for the just-sent (domain, id),
 * only for mirrored domains; a client-only channel (`preferences`) is skipped.
 */
class ReconcileOnDrainTest :
    FunSpec({

        test("after a drain sends a mirrored-domain op, the engine targeted-fetches that entity") {
            runBlocking {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val db = createInMemoryTestDatabase()
                try {
                    val fetches = MutableSharedFlow<Pair<String, List<String>>>(replay = 64)
                    val catchUp = RecordingCatchUp(fetches)
                    val queue =
                        PendingOperationQueue(
                            dao = db.pendingOperationV2Dao(),
                            sender = PendingOperationSender { AppResult.Success(Unit) },
                        )
                    val state = SyncEngineState()
                    val sse = ReconcileFakeSseClient(state)
                    val engine = buildReconcileEngine(db, queue, state, sse, scope, catchUp)

                    // Enqueued before start → only the connection-up drain sends it. On send the engine
                    // must reconcile the just-sent (tags, t1) via a targeted fetchTransient.
                    queue.enqueue(reconcileTagsChannel, "t1", OpKind.Upsert, "{}", "u1")

                    engine.start(currentUserId = "u1")

                    withTimeout(TIMEOUT_SECONDS.seconds) {
                        fetches.first { (domain, ids) -> domain == "tags" && ids.contains("t1") }
                    }
                    // The op actually drained (removed), proving the reconcile followed a real send.
                    db.pendingOperationV2Dao().countDispatchable(maxAttempts = 5) shouldBe 0
                } finally {
                    scope.cancel()
                    scope.coroutineContext.job.children
                        .forEach { it.join() }
                    db.close()
                }
            }
        }

        test("a client-only-channel op (preferences) sends but triggers NO targeted fetch") {
            runBlocking {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val db = createInMemoryTestDatabase()
                try {
                    val fetches = MutableSharedFlow<Pair<String, List<String>>>(replay = 64)
                    val catchUp = RecordingCatchUp(fetches)
                    val sent = MutableSharedFlow<String>(replay = 64)
                    val queue =
                        PendingOperationQueue(
                            dao = db.pendingOperationV2Dao(),
                            sender =
                                PendingOperationSender { op ->
                                    sent.tryEmit(op.clientOpId)
                                    AppResult.Success(Unit)
                                },
                        )
                    val state = SyncEngineState()
                    val sse = ReconcileFakeSseClient(state)
                    val engine = buildReconcileEngine(db, queue, state, sse, scope, catchUp)

                    val opId = queue.enqueue(reconcilePreferencesChannel, "u1", OpKind.Update, "{}", "u1")

                    engine.start(currentUserId = "u1")

                    // Prove the op drained (the reconcile decision point was reached with a real send).
                    withTimeout(TIMEOUT_SECONDS.seconds) { sent.first { it == opId } }
                    db.pendingOperationV2Dao().get(opId) shouldBe null

                    // No registered "preferences" handler → the engine must NOT fetch. Give it ample time.
                    delay(QUIET_WINDOW_MILLIS)
                    fetches.replayCache.none { it.first == "preferences" } shouldBe true
                } finally {
                    scope.cancel()
                    scope.coroutineContext.job.children
                        .forEach { it.join() }
                    db.close()
                }
            }
        }
    })

private fun buildReconcileEngine(
    db: com.calypsan.listenup.client.data.local.db.ListenUpDatabase,
    queue: PendingOperationQueue,
    state: SyncEngineState,
    sse: SseClient,
    scope: CoroutineScope,
    catchUp: CatchUp,
): SyncEngine {
    val registry = ClientSyncDomainRegistry()
    registry.register(ReconcileTagHandler)
    val store = SyncCursorStore(db.syncCursorDao())
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

/** [CatchUp] that records every [fetchTransient] call as (domainName, ids); everything else is a no-op. */
private class RecordingCatchUp(
    private val fetches: MutableSharedFlow<Pair<String, List<String>>>,
) : CatchUp {
    override suspend fun <T : Any> catchUp(handler: SyncDomainHandler<T>): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun <T : Any> catchUpFromZero(handler: SyncDomainHandler<T>): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun catchUpAll(registry: ClientSyncDomainRegistry): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun <T : Any> catchUpTransient(handler: SyncDomainHandler<T>): AppResult<Set<String>> = AppResult.Success(emptySet())

    override suspend fun <T : Any> fetchTransient(
        handler: SyncDomainHandler<T>,
        fetch: TargetedFetch,
    ): AppResult<Set<String>> {
        val ids = (fetch as? TargetedFetch.ByIds)?.ids ?: emptyList()
        fetches.tryEmit(handler.domainName to ids)
        return AppResult.Success(ids.toSet())
    }

    override suspend fun domains(): AppResult<List<String>> = AppResult.Success(emptyList())
}

private object ReconcileTagHandler : SyncDomainHandler<Tag> {
    override val domainName = "tags"
    override val payloadSerializer = Tag.serializer()

    override fun syncId(item: Tag): String = item.id

    override suspend fun onEvent(event: com.calypsan.listenup.api.sync.SyncEvent<Tag>): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun onCatchUpItem(
        item: Tag,
        isTombstone: Boolean,
    ): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun localDigestRows(maxRevision: Long): List<Pair<String, Long>> = emptyList()
}

/** Fake SSE client whose `connect()` transitions [state] to Connected — driving the engine's drain trigger. */
private class ReconcileFakeSseClient(
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
