package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.SyncFrame
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.client.data.sync.domains.OpKind
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer

private const val POLL_MS = 10L

/**
 * Regression guard for the heal-drain collector dying on its first failing heal.
 *
 * [SyncEngine]'s heal collector drains [PendingOperationQueue.observeHealRequests] — the DRIFT-1
 * repair for an optimistic edit the server rejected. It was the ONE collector in the engine with no
 * per-item guard: a throw out of [DrainReconciler.healEntity] killed the coroutine, and because
 * `ensureHealDrain` is only re-entered from `runStart` (which no-ops for an already-started user),
 * the collector stayed dead for the rest of the process while the UNLIMITED heal channel grew behind
 * it. On Kotlin/Native the uncaught throw kills the process outright.
 *
 * This test dismisses two ops in a row; the first heal's fetch throws. The second heal must still be
 * processed — proof the collector survived.
 */
class HealDrainResilienceTest :
    FunSpec({
        // The heal collector is a long-lived background coroutine reacting to a channel; like
        // SyncEngineAuthGateTest this drives a real Dispatchers.Default scope and polls with withTimeout.
        test("a heal whose fetch throws does not kill the collector: the next heal request is still processed") {
            runBlocking {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val db = createInMemoryTestDatabase()
                try {
                    val fetched = CopyOnWriteArrayList<String>()
                    val catchUp = ThrowOnFirstFetchCatchUp(fetched)
                    val registry = ClientSyncDomainRegistry()
                    registry.register(HealTagHandler())
                    val store = SyncCursorStore(db.syncCursorDao())
                    val queue =
                        PendingOperationQueue(
                            dao = db.pendingOperationV2Dao(),
                            // Parks (never removes) an op should a drain wave race the dismiss below, so
                            // both ops are still in the queue when dismissOp looks them up.
                            sender = PendingOperationSender { AppResult.Failure(TransportError.NetworkUnavailable()) },
                        )
                    val state = SyncEngineState()
                    val engine =
                        SyncEngine(
                            registry = registry,
                            queue = queue,
                            state = state,
                            store = store,
                            catchUp = catchUp,
                            syncStreamClient = HealFakeSse(state),
                            reconciler = noopSyncReconciler(registry, store, catchUp),
                            dispatcher =
                                SyncEventDispatcher(
                                    registry = registry,
                                    state = state,
                                    cursorAdvance = { domain, rev -> store.setCursor(domain, rev) },
                                ),
                            presenceRefreshSignal = PresenceRefreshSignal(),
                            scope = scope,
                        )

                    // start() is what wires ensureHealDrain.
                    engine.start(currentUserId = "u1")

                    // signal = false: no enqueue-triggered drain, so the ops sit queued until dismissed.
                    val firstOp = queue.enqueue(OutboxChannels.Tags, "t1", OpKind.Update, "{}", "u1", signal = false)
                    val secondOp = queue.enqueue(OutboxChannels.Tags, "t2", OpKind.Update, "{}", "u1", signal = false)
                    // Each dismiss emits one heal request. The first heal's fetch throws; the second must
                    // still be processed.
                    queue.dismissOp(firstOp)
                    queue.dismissOp(secondOp)

                    // RED before the fix: the collector died on t1's throw, so t2 never arrives and this
                    // poll times out. GREEN after: the guard logs t1's failure and t2 heals.
                    withTimeout(10_000) { while ("t2" !in fetched) delay(POLL_MS) }
                    fetched shouldContain "t2"

                    engine.stopAndJoin()
                } finally {
                    scope.cancel()
                    scope.coroutineContext.job.children
                        .forEach { it.join() }
                    db.close()
                }
            }
        }
    })

/** [CatchUp] fake whose targeted fetch throws on its first call and records every call after. */
private class ThrowOnFirstFetchCatchUp(
    private val fetched: MutableList<String>,
) : CatchUp {
    private var calls = 0

    override suspend fun <T : Any> fetchTransient(
        handler: SyncDomainHandler<T>,
        fetch: TargetedFetch,
    ): AppResult<Set<String>> {
        calls++
        if (calls == 1) error("simulated heal fetch failure")
        fetched += (fetch as TargetedFetch.ByIds).ids.single()
        return AppResult.Success(emptySet())
    }

    override suspend fun catchUpAll(registry: ClientSyncDomainRegistry): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun <T : Any> catchUp(handler: SyncDomainHandler<T>): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun <T : Any> catchUpFromZero(handler: SyncDomainHandler<T>): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun <T : Any> catchUpTransient(handler: SyncDomainHandler<T>): AppResult<Set<String>> = AppResult.Success(emptySet())

    override suspend fun domains(): AppResult<List<String>> = AppResult.Success(emptyList())
}

/** Minimal no-op tag handler so `healEntity`'s registry lookup for "tags" resolves. */
private class HealTagHandler : SyncDomainHandler<Tag> {
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

/** Fake stream client mirroring production's `connect()`-flips-state semantics. */
private class HealFakeSse(
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
