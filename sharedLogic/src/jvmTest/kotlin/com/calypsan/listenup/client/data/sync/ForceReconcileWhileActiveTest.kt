package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.sync.SyncFrame
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.DomainDigest
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.api.ScannerService
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.repository.SyncRepositoryImpl
import com.calypsan.listenup.client.device.DeviceInfoProvider
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.playback.ListeningEventRecorder
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import kotlinx.coroutines.flow.emptyFlow
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer

/**
 * Regression guard for the post-restore stale-Room bug.
 *
 * A server-side restore is a wholesale DB swap that is NOT announced on the sync firehose,
 * so the only way the client refreshes Room is an explicit digest reconcile. The recovery
 * path is [SyncRepositoryImpl.forceFullResync] → [SyncEngine.forceReconcile]. The trap:
 * by the time an admin triggers a restore the engine is already running, so
 * [SyncEngine.start] hits its same-user no-op guard and never reaches the reconcile step.
 *
 * Pre-fix `forceFullResync` delegated only to `start()`, so it did nothing while the engine
 * was active — restore reported success but Room stayed stale until a cold restart. This test
 * pins that [SyncEngine.forceReconcile] DOES run the digest reconcile (re-pulling a drifted
 * domain) even after the engine is already started and `start()` would no-op.
 */
class ForceReconcileWhileActiveTest :
    FunSpec({

        test("forceReconcile re-pulls a drifted domain even when the engine is already active (start would no-op)") {
            runBlocking {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val db = createInMemoryTestDatabase()
                try {
                    val catchUp = CountingFromZeroCatchUp()
                    val state = SyncEngineState()
                    val sse = FakeForceReconcileSyncStreamClient(state)

                    // The reconciler sees a stored cursor (so reconcileAll does not early-return)
                    // and a server digest that disagrees with the local rows for "tags" — i.e.
                    // the domain has drifted, exactly as a restore would leave it.
                    val max = 100L
                    val tagRows = listOf("t1" to 5L)
                    val driftedServerDigest = DigestComputer.compute(max, listOf("t1" to 999L))

                    val handler = DriftingTagHandler(rows = tagRows)
                    val registry = ClientSyncDomainRegistry()
                    registry.register(handler)
                    val store = SyncCursorStore(db.syncCursorDao())
                    store.setCursor("tags", max)

                    val reconciler =
                        SyncReconciler(
                            registry = registry,
                            store = store,
                            digestClient = fakeDigestClient(mapOf("tags" to driftedServerDigest)),
                            catchUp = catchUp,
                        )
                    val engine = buildEngine(db, registry, store, state, catchUp, sse, reconciler, scope)

                    // Start the engine, then start again: the second call hits the same-user
                    // no-op guard and never reaches start()'s own reconcile step.
                    engine.start(currentUserId = "user-a")
                    val afterStart = catchUp.fromZeroInvocations.get()
                    engine.start(currentUserId = "user-a")
                    catchUp.fromZeroInvocations.get() shouldBe afterStart

                    // The force path must still reconcile despite the no-op — this is the fix.
                    engine.forceReconcile()

                    catchUp.fromZeroInvocations.get() shouldBe afterStart + 1
                } finally {
                    scope.cancel()
                    scope.coroutineContext.job.children
                        .forEach { it.join() }
                    db.close()
                }
            }
        }

        // The ABS-import live-progress bug. Import completion writes playback positions + listening
        // events server-side under `FirehoseSuppressed` (no SSE push), at revisions ABOVE the client's
        // cursor. `refreshListeningHistory` must drain those forward so Continue Listening updates live.
        // Pre-fix it delegated to `forceFullResync` → `forceReconcile` (a DIGEST compare at the stale
        // cursor): both local and server digests exclude the beyond-cursor rows, so no drift is seen and
        // nothing is pulled — the progress only appeared after a cold restart (whose `start()` runs a
        // forward catch-up). The fix routes `refreshListeningHistory` through the forward catch-up
        // (`handleCursorStale` → `catchUpAll`), which runs even though `start()` no-ops on the already-
        // running engine.
        test("refreshListeningHistory forces a forward catch-up even when the engine is already running") {
            runBlocking {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val db = createInMemoryTestDatabase()
                try {
                    val catchUp = CountingFromZeroCatchUp()
                    val state = SyncEngineState()
                    val sse = FakeForceReconcileSyncStreamClient(state)

                    // Digests AGREE at the cursor (the import's rows are beyond `max`), so a digest
                    // reconcile sees no drift — it is structurally blind to the suppressed import.
                    val max = 100L
                    val rows = listOf("p1" to 5L)
                    val agreeingDigest = DigestComputer.compute(max, rows)
                    val handler = DriftingTagHandler(rows = rows)
                    val registry = ClientSyncDomainRegistry()
                    registry.register(handler)
                    val store = SyncCursorStore(db.syncCursorDao())
                    store.setCursor("tags", max)
                    val reconciler =
                        SyncReconciler(
                            registry = registry,
                            store = store,
                            digestClient = fakeDigestClient(mapOf("tags" to agreeingDigest)),
                            catchUp = catchUp,
                        )
                    val engine = buildEngine(db, registry, store, state, catchUp, sse, reconciler, scope)

                    // Real recorder over the empty in-memory DB — recoverOrphan() is a no-op (no span).
                    val recorder =
                        ListeningEventRecorder(
                            listeningEventDao = db.listeningEventDao(),
                            tentativeSpanDao = db.tentativeSpanDao(),
                            transactionRunner = RoomTransactionRunner(db),
                            enqueue = { _, _, _ -> },
                            currentUserId = { "user-a" },
                            deviceInfo = DeviceInfoProvider { error("device info not used in this test") },
                        )
                    val authSession = mock<AuthSession> { everySuspend { getUserId() } returns "user-a" }
                    val ftsPopulator =
                        mock<FtsPopulatorContract> {
                            everySuspend { rebuildIfEmpty() } returns Unit
                            everySuspend { rebuildAll() } returns Unit
                            every { observeContentChanges() } returns emptyFlow()
                            everySuspend { snapshotWatermark() } returns SearchIndexWatermark(0L, 0L, 0L, 0L)
                        }
                    // Un-stubbed service: observeProgress()/lastScanResult() throw, so the scan-progress
                    // observer folds to RpcEvent.Error and dies harmlessly — refreshListeningHistory under
                    // test never depends on the scanner channel.
                    val scannerChannel = RpcChannel.forTest(mock<ScannerService>())

                    val repo =
                        SyncRepositoryImpl(
                            syncEngine = engine,
                            reevaluateConnection = {},
                            syncEngineState = state,
                            authSession = authSession,
                            listeningEventRecorder = recorder,
                            scannerChannel = scannerChannel,
                            bookDao = db.bookDao(),
                            libraryDao = db.libraryDao(),
                            listeningEventDao = db.listeningEventDao(),
                            ftsPopulator = ftsPopulator,
                            coverPresenceReconciler =
                                CoverPresenceReconciler(
                                    bookDao = db.bookDao(),
                                    imageStorage = mock<ImageStorage> { every { listCoverBookIds() } returns emptySet() },
                                ),
                            scope = scope,
                        )

                    // Engine already running (the post-import reality): the next start() no-ops.
                    engine.start(currentUserId = "user-a")
                    val afterStart = catchUp.allInvocations.get()

                    repo.refreshListeningHistory()

                    // refreshListeningHistory forces a forward catch-up (handleCursorStale → catchUpAll),
                    // despite start()'s no-op. Pre-fix it did a digest reconcile that added NO catch-up
                    // (delta 0), stranding the import progress — so the guard is "at least one forward
                    // catch-up was forced". It must be `>=`, not `==`: handleCursorStale's recovery also
                    // reseeds and RECONNECTS the SSE, and since Phase 0 every reconnect edge fires a forced
                    // lifecycleReconcile (another catchUpAll) on the reconnect-refresh collector — async and
                    // unjoined, so the count settles at +1 or +2 by timing. The invariant under test is that
                    // a forward catch-up happened, not the exact number of downstream reconnect heals.
                    catchUp.allInvocations.get() shouldBeGreaterThanOrEqual afterStart + 1
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
    registry: ClientSyncDomainRegistry,
    store: SyncCursorStore,
    state: SyncEngineState,
    catchUp: CatchUp,
    sse: SyncStreamClient,
    reconciler: SyncReconciler,
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
        sseClient = sse,
        reconciler = reconciler,
        dispatcher = dispatcher,
        presenceRefreshSignal = PresenceRefreshSignal(),
        scope = scope,
    )
}

/**
 * [DomainDigestClient] backed by an in-memory map: a GET to `/api/v1/sync/<domain>/digest`
 * responds with the pre-set [DomainDigest] for that domain, or 404 if absent.
 * Mirrors `SyncReconcilerTest`'s fake.
 */
private fun fakeDigestClient(domainDigests: Map<String, DomainDigest>): DomainDigestClient {
    val mockClient =
        HttpClient(
            MockEngine { req ->
                val domain =
                    req.url.pathSegments
                        .dropLast(1)
                        .last()
                val digest = domainDigests[domain]
                if (digest != null) {
                    respond(
                        content = contractJson.encodeToString(DomainDigest.serializer(), digest),
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                } else {
                    respond(content = "not found", status = io.ktor.http.HttpStatusCode.NotFound)
                }
            },
        ) { install(ContentNegotiation) { json(contractJson) } }
    return DomainDigestClient(
        httpClientProvider = { mockClient },
        serverUrlProvider = { "http://test" },
    )
}

/**
 * Recording fake that counts `catchUpFromZero` invocations — the signal for "did the
 * reconciler detect drift and trigger a re-pull?" `catchUpAll` succeeds without counting
 * so `start()`'s own catch-up doesn't pollute the assertion.
 */
private class CountingFromZeroCatchUp : CatchUp {
    val fromZeroInvocations = AtomicInteger(0)

    /** Counts [catchUpAll] — the forward drain that pulls rows beyond the persisted cursor. */
    val allInvocations = AtomicInteger(0)

    override suspend fun <T : Any> catchUp(handler: SyncDomainHandler<T>): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun <T : Any> catchUpFromZero(handler: SyncDomainHandler<T>): AppResult<Unit> {
        fromZeroInvocations.incrementAndGet()
        return AppResult.Success(Unit)
    }

    override suspend fun catchUpAll(registry: ClientSyncDomainRegistry): AppResult<Unit> {
        allInvocations.incrementAndGet()
        return AppResult.Success(Unit)
    }

    override suspend fun <T : Any> catchUpTransient(handler: SyncDomainHandler<T>): AppResult<Set<String>> = AppResult.Success(emptySet())

    override suspend fun domains(): AppResult<List<String>> = AppResult.Success(emptyList())
}

/** Minimal handler whose [localDigestRows] returns fixed rows so the reconciler can compare digests. */
private class DriftingTagHandler(
    private val rows: List<Pair<String, Long>>,
) : SyncDomainHandler<Tag> {
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

    override suspend fun localDigestRows(maxRevision: Long): List<Pair<String, Long>> = rows
}

/** Fake SSE client mirroring production's `connect()`-flips-state semantics. */
private class FakeForceReconcileSyncStreamClient(
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
