package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.SyncFrame
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.test.db.closeAfterCancelling
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

private const val POSITIONS = "playback_positions"
private const val TAGS = "tags"
private const val SETTLE_TIMEOUT_MS = 10_000L

/**
 * The firehose resumes from the highest revision of ANY domain, but a pull is just "rows above my cursor", taken
 * one domain after another. A row written to the digest-less positions domain after its pull and before the pass
 * ends sits above the positions cursor and below the resume point: the stream never replays it and no digest
 * heals it. These specs simulate that interleaving against a scripted server and pin the invariant: the positions
 * cursor never passes a server row this device has not applied, and the row does arrive.
 */
class SyncEngineCatchUpGapTest :
    FunSpec({
        test("a position written during the start pass is pulled after connecting, not stepped over") {
            runBlocking {
                withGapRig { rig ->
                    rig.server.write(POSITIONS, 100L)
                    rig.server.write(TAGS, 105L)
                    // Another device saves a position after this device's positions pull, before the pass ends.
                    rig.catchUp.afterPull = { domain -> if (domain == POSITIONS) rig.server.write(POSITIONS, 101L) }
                    rig.stream.onConnect = { rig.goLive(positionsAt = 110L, tagsAt = 111L) }

                    rig.engine.start(currentUserId = "user-a")
                    rig.awaitSettled()

                    rig.applied shouldContain (POSITIONS to 101L)
                    rig.violations.shouldBeEmpty()
                    rig.store.getCursor(POSITIONS) shouldBe 110L
                }
            }
        }

        test("a position written during a stale-cursor recovery pass is pulled after reconnecting, not stepped over") {
            runBlocking {
                withGapRig { rig ->
                    rig.server.write(POSITIONS, 100L)
                    rig.server.write(TAGS, 105L)
                    rig.engine.start(currentUserId = "user-a")

                    rig.server.write(POSITIONS, 120L)
                    rig.server.write(TAGS, 125L)
                    rig.catchUp.afterPull = { domain -> if (domain == POSITIONS) rig.server.write(POSITIONS, 121L) }
                    rig.stream.onConnect = { rig.goLive(positionsAt = 130L, tagsAt = 131L) }

                    rig.engine.handleCursorStale()
                    rig.awaitSettled()

                    rig.applied shouldContain (POSITIONS to 121L)
                    rig.violations.shouldBeEmpty()
                    rig.store.getCursor(POSITIONS) shouldBe 130L
                }
            }
        }
    })

/** The rows the scripted server holds, by domain, each a revision of one global counter. */
private class ScriptedServer {
    val rows = CopyOnWriteArrayList<Pair<String, Long>>()

    fun write(
        domain: String,
        revision: Long,
    ) {
        rows += domain to revision
    }

    fun rowsAbove(
        domain: String,
        cursor: Long?,
    ): List<Long> = rows.filter { it.first == domain && it.second > (cursor ?: 0L) }.map { it.second }.sorted()
}

/**
 * A pull is "rows above my cursor, applied, then the cursor set to the last" — the production shape, with no
 * snapshot. A pull outside a pass (a top-up) first waits for [liveApplied], so the live frame always lands before
 * it: the order that buries the row when nothing holds the cursor.
 */
private class ScriptedCatchUp(
    private val server: ScriptedServer,
    private val store: SyncCursorStore,
    private val applied: MutableSet<Pair<String, Long>>,
) : CountingCatchUp() {
    var afterPull: (String) -> Unit = {}

    // Open until a scenario goes live, so a top-up with no live tail scripted never waits.
    var liveApplied = CompletableDeferred(Unit)

    private suspend fun pull(domain: String) {
        val rows = server.rowsAbove(domain, store.getCursor(domain))
        rows.forEach { applied += domain to it }
        rows.lastOrNull()?.let { store.setCursor(domain, it) }
    }

    override suspend fun <T : Any> catchUp(handler: SyncDomainHandler<T>): AppResult<Unit> {
        liveApplied.await()
        pull(handler.domainName)
        return AppResult.Success(Unit)
    }

    override suspend fun catchUpAll(registry: ClientSyncDomainRegistry): AppResult<Unit> {
        super.catchUpAll(registry)
        registry.registeredDomains().forEach { domain ->
            pull(domain)
            afterPull(domain)
        }
        afterPull = {}
        return AppResult.Success(Unit)
    }
}

/** Delivers only what the test emits on connect; [onConnect] scripts the live tail. */
private class ScriptedStreamClient(
    private val state: SyncEngineState,
) : SyncStreamClient {
    private val flow = MutableSharedFlow<SyncFrame>(extraBufferCapacity = 16)
    override val frames: SharedFlow<SyncFrame> = flow.asSharedFlow()
    var onConnect: () -> Unit = {}
    private var seeded: Long? = null

    fun emit(frame: SyncFrame) {
        check(flow.tryEmit(frame)) { "frame buffer full" }
    }

    override fun seedLastEventId(initial: Long?) {
        seeded = initial
    }

    override fun connect() {
        state.setConnection(ConnectionState.Connected(lastEventId = seeded))
        onConnect()
        onConnect = {}
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

/** A Tag-shaped handler under [domainName] that records every live apply. */
private class RecordingGapHandler(
    override val domainName: String,
    override val hasDigestBackstop: Boolean,
    private val onApplied: (Long) -> Unit,
) : SyncDomainHandler<Tag> {
    override val payloadSerializer = Tag.serializer()

    override fun syncId(item: Tag): String = item.id

    override suspend fun onEvent(event: SyncEvent<Tag>): AppResult<Unit> {
        onApplied(event.revision)
        return AppResult.Success(Unit)
    }

    override suspend fun onCatchUpItem(
        item: Tag,
        isTombstone: Boolean,
    ): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun localDigestRows(maxRevision: Long): List<Pair<String, Long>>? = if (hasDigestBackstop) emptyList() else null
}

private class GapRig(
    db: ListenUpDatabase,
    scope: CoroutineScope,
) {
    val server = ScriptedServer()
    val store = SyncCursorStore(db.syncCursorDao())
    val applied: MutableSet<Pair<String, Long>> = ConcurrentHashMap.newKeySet()
    val violations = CopyOnWriteArrayList<String>()
    private val state = SyncEngineState()
    val stream = ScriptedStreamClient(state)
    val catchUp = ScriptedCatchUp(server, store, applied)
    private var settled = CompletableDeferred<Unit>()
    private var tagsSettleAt = Long.MAX_VALUE

    private val registry =
        ClientSyncDomainRegistry().apply {
            register(
                RecordingGapHandler(POSITIONS, hasDigestBackstop = false) { rev ->
                    applied += POSITIONS to rev
                    catchUp.liveApplied.complete(Unit)
                },
            )
            register(
                RecordingGapHandler(TAGS, hasDigestBackstop = true) { rev ->
                    applied += TAGS to rev
                    // The collector is sequential: the tags frame that follows the positions frame marks it done.
                    if (rev == tagsSettleAt) settled.complete(Unit)
                },
            )
        }

    // The invariant: the positions cursor never moves past a server row this device has not applied.
    private suspend fun advanceChecked(
        domain: String,
        revision: Long,
    ) {
        if (domain == POSITIONS) {
            val skipped = server.rowsAbove(POSITIONS, null).filter { it <= revision && POSITIONS to it !in applied }
            if (skipped.isNotEmpty()) violations += "positions cursor advanced to $revision over unapplied $skipped"
        }
        store.advanceFromLiveFrame(domain, revision)
    }

    val engine =
        SyncEngine(
            registry = registry,
            queue =
                PendingOperationQueue(
                    dao = db.pendingOperationV2Dao(),
                    sender = PendingOperationSender { AppResult.Failure(SyncError.NotFound(domain = TAGS, entityId = "t")) },
                ),
            state = state,
            store = store,
            catchUp = catchUp,
            syncStreamClient = stream,
            reconciler = noopSyncReconciler(registry, store, catchUp),
            dispatcher = SyncEventDispatcher(registry = registry, state = state, cursorAdvance = ::advanceChecked),
            presenceRefreshSignal = PresenceRefreshSignal(),
            scope = scope,
        )

    /** Server writes a position and a tag above the resume point; the stream delivers both, positions first. */
    fun goLive(
        positionsAt: Long,
        tagsAt: Long,
    ) {
        server.write(POSITIONS, positionsAt)
        server.write(TAGS, tagsAt)
        catchUp.liveApplied = CompletableDeferred()
        settled = CompletableDeferred()
        tagsSettleAt = tagsAt
        stream.emit(frameAt(POSITIONS, positionsAt))
        stream.emit(frameAt(TAGS, tagsAt))
    }

    suspend fun awaitSettled() = withTimeout(SETTLE_TIMEOUT_MS) { settled.await() }

    private fun frameAt(
        domain: String,
        rev: Long,
    ): SyncFrame {
        val event =
            SyncEvent.Created(
                id = "$domain-$rev",
                revision = rev,
                occurredAt = 1L,
                clientOpId = null,
                payload = Tag("$domain-$rev", "n", "n", rev, 1L),
            )
        return SyncFrame(revision = rev, domain = domain, json = contractJson.encodeToString(SyncEvent.serializer(Tag.serializer()), event))
    }
}

private suspend fun withGapRig(block: suspend (GapRig) -> Unit) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = createInMemoryTestDatabase()
    try {
        block(GapRig(db, scope))
    } finally {
        db.closeAfterCancelling(scope)
    }
}
