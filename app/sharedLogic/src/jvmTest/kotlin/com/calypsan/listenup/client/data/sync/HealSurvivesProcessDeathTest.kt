package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.SyncFrame
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.sync.domains.OpKind
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.client.test.db.closeAfterCancelling
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer

private const val POLL_MS = 10L
private const val TIMEOUT_MS = 10_000L

/**
 * A heal owed to an entity whose optimistic edit the server refused must outlive the process that
 * owed it. "Process death" here is what it costs the app: every in-memory object — the queue, the
 * engine — is thrown away, and a fresh pair is built over the same database, as the next launch does.
 */
class HealSurvivesProcessDeathTest :
    FunSpec({
        test("a dead-letter's heal requested before the app is killed runs on the next start") {
            runBlocking {
                val db = createInMemoryTestDatabase()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    // First process: the server refuses the edit, the op dead-letters and a heal is owed —
                    // then the process dies before any engine drained it.
                    val doomed = queueOver(db, refusingSender)
                    doomed.enqueue(OutboxChannels.Tags, "t1", OpKind.Update, "{}", "u1", signal = false)
                    doomed.drain()

                    // Next launch.
                    val fetched = CopyOnWriteArrayList<String>()
                    val engine = engineOver(db, queueOver(db, refusingSender), DeathRecordingCatchUp(fetched), scope)
                    engine.start(currentUserId = "u1")

                    withTimeout(TIMEOUT_MS) { while ("t1" !in fetched) delay(POLL_MS) }
                    fetched shouldContainExactly listOf("t1")
                    engine.stopAndJoin()
                } finally {
                    db.closeAfterCancelling(scope)
                }
            }
        }

        test("a dismissal's heal requested before the app is killed runs on the next start") {
            runBlocking {
                val db = createInMemoryTestDatabase()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    val doomed = queueOver(db, refusingSender)
                    val opId = doomed.enqueue(OutboxChannels.Tags, "t2", OpKind.Update, "{}", "u1", signal = false)
                    doomed.dismissOp(opId)

                    val fetched = CopyOnWriteArrayList<String>()
                    val engine = engineOver(db, queueOver(db, refusingSender), DeathRecordingCatchUp(fetched), scope)
                    engine.start(currentUserId = "u1")

                    withTimeout(TIMEOUT_MS) { while ("t2" !in fetched) delay(POLL_MS) }
                    fetched shouldContainExactly listOf("t2")
                    engine.stopAndJoin()
                } finally {
                    db.closeAfterCancelling(scope)
                }
            }
        }

        test("a heal whose fetch fails stays owed and runs on the next start; a heal that ran is not repeated") {
            runBlocking {
                val db = createInMemoryTestDatabase()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    val doomed = queueOver(db, refusingSender)
                    doomed.enqueue(OutboxChannels.Tags, "t3", OpKind.Update, "{}", "u1", signal = false)
                    doomed.drain()

                    // Second process: the server can't be reached, so the heal fetch fails.
                    val offline = DeathOfflineCatchUp()
                    val second = engineOver(db, queueOver(db, refusingSender), offline, scope)
                    second.start(currentUserId = "u1")
                    withTimeout(TIMEOUT_MS) { while (offline.attempts == 0) delay(POLL_MS) }
                    second.stopAndJoin()
                    queueOver(db, refusingSender).pendingHeals().map { it.entity.entityId } shouldContainExactly
                        listOf("t3")

                    // Third process: online again, the heal runs and is settled.
                    val fetched = CopyOnWriteArrayList<String>()
                    val third = engineOver(db, queueOver(db, refusingSender), DeathRecordingCatchUp(fetched), scope)
                    third.start(currentUserId = "u1")
                    withTimeout(TIMEOUT_MS) { while ("t3" !in fetched) delay(POLL_MS) }
                    third.stopAndJoin()
                    withTimeout(TIMEOUT_MS) {
                        while (queueOver(db, refusingSender).pendingHeals().isNotEmpty()) delay(POLL_MS)
                    }
                    queueOver(db, refusingSender).pendingHeals().shouldBeEmpty()
                } finally {
                    db.closeAfterCancelling(scope)
                }
            }
        }
    })

/** The server refuses every edit outright, so one drain wave dead-letters it. */
private val refusingSender =
    PendingOperationSender { AppResult.Failure(SyncError.NotFound(domain = "tags", entityId = "any")) }

private fun queueOver(
    db: ListenUpDatabase,
    sender: PendingOperationSender,
) = PendingOperationQueue(dao = db.pendingOperationV2Dao(), sender = sender)

private fun engineOver(
    db: ListenUpDatabase,
    queue: PendingOperationQueue,
    catchUp: CatchUp,
    scope: CoroutineScope,
): SyncEngine {
    val registry = ClientSyncDomainRegistry()
    registry.register(DeathTagHandler())
    val store = SyncCursorStore(db.syncCursorDao())
    val state = SyncEngineState()
    return SyncEngine(
        registry = registry,
        queue = queue,
        state = state,
        store = store,
        catchUp = catchUp,
        syncStreamClient = DeathFakeStream(state),
        reconciler = noopSyncReconciler(registry, store, catchUp),
        dispatcher = SyncEventDispatcher(registry = registry, state = state, cursorAdvance = liveFrameCursorAdvance(store)),
        presenceRefreshSignal = PresenceRefreshSignal(),
        scope = scope,
    )
}

/** Records the entity id of every targeted fetch; every other pull is empty. */
private class DeathRecordingCatchUp(
    private val fetched: MutableList<String>,
) : DeathEmptyCatchUp() {
    override suspend fun <T : Any> fetchTransient(
        handler: SyncDomainHandler<T>,
        fetch: TargetedFetch,
    ): AppResult<Set<String>> {
        fetched += (fetch as TargetedFetch.ByIds).ids
        return AppResult.Success(emptySet())
    }
}

/** Every targeted fetch fails as the server being unreachable. */
private class DeathOfflineCatchUp : DeathEmptyCatchUp() {
    @Volatile var attempts = 0

    override suspend fun <T : Any> fetchTransient(
        handler: SyncDomainHandler<T>,
        fetch: TargetedFetch,
    ): AppResult<Set<String>> {
        attempts++
        return AppResult.Failure(TransportError.NetworkUnavailable())
    }
}

private abstract class DeathEmptyCatchUp : CatchUp {
    override suspend fun catchUpAll(registry: ClientSyncDomainRegistry): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun <T : Any> catchUp(handler: SyncDomainHandler<T>): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun <T : Any> catchUpFromZero(handler: SyncDomainHandler<T>): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun <T : Any> catchUpTransient(handler: SyncDomainHandler<T>): AppResult<Set<String>> = AppResult.Success(emptySet())

    override suspend fun domains(): AppResult<List<String>> = AppResult.Success(emptyList())
}

/** Minimal tag handler so the heal's registry lookup for "tags" resolves to a by-id fetch. */
private class DeathTagHandler : SyncDomainHandler<Tag> {
    override val domainName = "tags"
    override val payloadSerializer: KSerializer<Tag> = Tag.serializer()

    override fun syncId(item: Tag): String = item.id

    override suspend fun onEvent(event: SyncEvent<Tag>): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun onCatchUpItem(
        item: Tag,
        isTombstone: Boolean,
    ): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun localDigestRows(maxRevision: Long): List<Pair<String, Long>> = emptyList()
}

/** Fake stream client mirroring production's `connect()`-flips-state semantics. */
private class DeathFakeStream(
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
