package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.sync.SyncFrame
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.client.data.sync.domains.AccessDeltaPolicy
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
import com.calypsan.listenup.client.data.sync.testing.awaitUntil
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.serializer

// "books" is registered as an ACCESS-GATED mirrored handler (implements AccessFilteredSyncHandler) —
// the only kind the server's access-filtered `pullByIds` can serve, so the only kind reconcile-on-
// drain targeted-fetches. "tags" is a mirrored but NON-gated handler (userScoped/global class) that
// is skipped. "preferences" is a real client-only channel with no sync handler — also skipped.
private val reconcileBooksChannel = OutboxChannel("books", String.serializer(), setOf(OpKind.Upsert), idempotent = true)
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
 * lifecycle digest. GREEN: exactly one targeted `?ids=` fetch fires for the just-sent (domain, id) —
 * but only for an ACCESS-GATED domain the server can serve; a non-gated mirrored domain (`tags`) and
 * a client-only channel (`preferences`) are both skipped (they converge via catch-up / newer-wins).
 */
class ReconcileOnDrainTest :
    FunSpec({

        test("a drain reconciles an access-gated domain but skips a non-gated one") {
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
                    val sse = ReconcileFakeSyncStreamClient(state)
                    val engine = buildReconcileEngine(db, queue, state, sse, scope, catchUp)

                    // Both enqueued before start → the same connection-up drain wave sends both. The
                    // engine must reconcile the access-gated (books, b1) via a targeted fetchTransient
                    // and must NOT fetch the non-gated (tags, t1).
                    queue.enqueue(reconcileBooksChannel, "b1", OpKind.Upsert, "{}", "u1")
                    queue.enqueue(reconcileTagsChannel, "t1", OpKind.Upsert, "{}", "u1")

                    engine.start(currentUserId = "u1")

                    withTimeout(TIMEOUT_SECONDS.seconds) {
                        fetches.first { (domain, ids) -> domain == "books" && ids.contains("b1") }
                    }
                    // Both ops drained in this wave (books fetch fired only after the wave processed
                    // its whole sentEntities set), so tags was seen-and-skipped, not merely pending.
                    db.pendingOperationV2Dao().countDispatchable(maxAttempts = 5) shouldBe 0
                    fetches.replayCache.none { it.first == "tags" } shouldBe true
                } finally {
                    scope.cancel()
                    scope.coroutineContext.job.children
                        .forEach { it.join() }
                    db.close()
                }
            }
        }

        test("lifecycleReconcile drains a parked outbox op with no SSE-connected edge (never stranded)") {
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
                    val sse = ReconcileFakeSyncStreamClient(state)
                    val engine = buildReconcileEngine(db, queue, state, sse, scope, catchUp)

                    // An op parks with the engine NOT started, so there is no connection-up edge and
                    // no enqueue trigger reaches a running collector — the two drain triggers that
                    // exist otherwise. This is the parked-and-stranded state: an edit whose one RPC
                    // send timed out while nothing else prods the queue.
                    val opId = queue.enqueue(reconcilePreferencesChannel, "u1", OpKind.Update, "{}", "u1")

                    // The manual/foreground path. Pre-fix this only PULLED (catch-up + reconcile +
                    // refresh) and the op stayed parked; post-fix it also drains, so the op sends.
                    engine.lifecycleReconcile(force = true)

                    withTimeout(TIMEOUT_SECONDS.seconds) { sent.first { it == opId } }
                    awaitUntil(TIMEOUT_SECONDS.seconds) { db.pendingOperationV2Dao().get(opId) == null }
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
                    val sse = ReconcileFakeSyncStreamClient(state)
                    val engine = buildReconcileEngine(db, queue, state, sse, scope, catchUp)

                    val opId = queue.enqueue(reconcilePreferencesChannel, "u1", OpKind.Update, "{}", "u1")

                    engine.start(currentUserId = "u1")

                    // Prove the op drained (the reconcile decision point was reached with a real send).
                    withTimeout(TIMEOUT_SECONDS.seconds) { sent.first { it == opId } }
                    // Await the row's removal rather than asserting it the instant the send is
                    // observed: the queue deletes AFTER the sender returns, so reading the DAO
                    // straight off `sent` races the delete — green locally, flaky under CI
                    // contention. Await the state we actually mean.
                    awaitUntil(TIMEOUT_SECONDS.seconds) { db.pendingOperationV2Dao().get(opId) == null }

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
    sse: SyncStreamClient,
    scope: CoroutineScope,
    catchUp: CatchUp,
): SyncEngine {
    val registry = ClientSyncDomainRegistry()
    registry.register(ReconcileGatedHandler)
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

/**
 * An access-gated stand-in (books). Implementing [AccessFilteredSyncHandler] is the signal
 * reconcile-on-drain gates its targeted fetch on; its delta methods are never reached by that path,
 * so they are trivial stubs.
 */
private object ReconcileGatedHandler :
    SyncDomainHandler<Tag>,
    AccessFilteredSyncHandler {
    override val domainName = "books"
    override val payloadSerializer = Tag.serializer()

    override fun syncId(item: Tag): String = item.id

    override suspend fun onEvent(event: com.calypsan.listenup.api.sync.SyncEvent<Tag>): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun onCatchUpItem(
        item: Tag,
        isTombstone: Boolean,
    ): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun localDigestRows(maxRevision: Long): List<Pair<String, Long>> = emptyList()

    override val deltaPolicy: AccessDeltaPolicy = AccessDeltaPolicy.LiveTailOnly("reconcile-on-drain test fake")

    override suspend fun localLiveIds(): Set<String> = emptySet()

    override suspend fun pruneTo(
        accessibleIds: Set<String>,
        now: Long,
    ) = Unit

    override suspend fun pruneWithin(
        candidateIds: Set<String>,
        accessibleIds: Set<String>,
        now: Long,
    ) = Unit
}

/** Fake SSE client whose `connect()` transitions [state] to Connected — driving the engine's drain trigger. */
private class ReconcileFakeSyncStreamClient(
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
