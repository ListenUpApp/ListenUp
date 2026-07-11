package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.local.db.TentativeSpanDao
import com.calypsan.listenup.client.data.local.db.TentativeSpanEntity
import com.calypsan.listenup.api.ScannerService
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.sync.CatchUp
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.ConnectionState
import com.calypsan.listenup.client.data.sync.CoverPresenceReconciler
import com.calypsan.listenup.client.data.sync.DomainDigestClient
import com.calypsan.listenup.client.data.sync.FtsPopulatorContract
import com.calypsan.listenup.client.data.sync.ParsedSseFrame
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.PendingOperationSender
import com.calypsan.listenup.client.data.sync.PresenceRefreshSignal
import com.calypsan.listenup.client.data.sync.SseClient
import com.calypsan.listenup.client.data.sync.SyncCursorStore
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import com.calypsan.listenup.client.data.sync.SyncEngine
import com.calypsan.listenup.client.data.sync.SyncEngineState
import com.calypsan.listenup.client.data.sync.SyncEventDispatcher
import com.calypsan.listenup.client.data.sync.SyncReconciler
import com.calypsan.listenup.client.device.DeviceInfoProvider
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.playback.ListeningEventRecorder
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.mock
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
 * Regression guard for the concurrent-orphan-recovery race.
 *
 * [SyncRepositoryImpl.startEngineForCurrentUser] can be called concurrently
 * (via [SyncRepositoryImpl.sync], [SyncRepositoryImpl.connectRealtime], and
 * [SyncRepositoryImpl.resetForNewLibrary]) on the multi-threaded [scope].
 *
 * Pre-fix the check-and-set on `orphanRecovered` was unguarded:
 *
 * ```kotlin
 * if (!orphanRecovered) {
 *     orphanRecovered = true          // not atomic with the read above
 *     listeningEventRecorder.recoverOrphan()
 * }
 * ```
 *
 * Two concurrent callers could both observe `false`, both set the flag, and
 * both invoke [ListeningEventRecorder.recoverOrphan] — violating the at-most-once
 * contract. The fix routes the read-and-set through a [kotlinx.coroutines.sync.Mutex].
 *
 * The test uses a [CountingTentativeSpanDao] to count [TentativeSpanDao.get]
 * invocations, which is the first operation [ListeningEventRecorder.recoverOrphan]
 * performs. With the mutex, only one concurrent caller enters the recovery body,
 * so [TentativeSpanDao.get] is called exactly once even under 8-way concurrency.
 */
class OrphanRecoveryRaceTest :
    FunSpec({

        test("concurrent connectRealtime() calls invoke recoverOrphan() exactly once") {
            runBlocking {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val db = createInMemoryTestDatabase()
                try {
                    val state = SyncEngineState()
                    val sse = FakeOrphanRaceSseClient(state)
                    val catchUp = NoOpCatchUp()
                    val engine = buildOrphanTestEngine(db, state, catchUp, sse, scope)

                    val countingSpanDao = CountingTentativeSpanDao(db.tentativeSpanDao())
                    val recorder =
                        ListeningEventRecorder(
                            listeningEventDao = db.listeningEventDao(),
                            tentativeSpanDao = countingSpanDao,
                            transactionRunner = RoomTransactionRunner(db),
                            enqueue = { _, _, _ -> },
                            currentUserId = { "user-test" },
                            deviceInfo = DeviceInfoProvider { error("device info not used in this test") },
                        )

                    val authSession = mock<AuthSession> { everySuspend { getUserId() } returns "user-test" }
                    val ftsPopulator =
                        mock<FtsPopulatorContract> {
                            everySuspend { rebuildIfEmpty() } returns Unit
                            everySuspend { rebuildAll() } returns Unit
                        }
                    val scannerChannel = RpcChannel.forTest(mock<ScannerService>())

                    val repo =
                        SyncRepositoryImpl(
                            syncEngine = engine,
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

                    // Launch 8 concurrent connectRealtime() calls — all race to enter
                    // startEngineForCurrentUser() at the same time. Without the mutex,
                    // multiple callers can observe orphanRecovered == false and all
                    // invoke recoverOrphan(). With the fix, exactly one does.
                    coroutineScope {
                        repeat(8) {
                            launch { repo.connectRealtime() }
                        }
                    }

                    // recoverOrphan() calls tentativeSpanDao.get() as its first operation.
                    // Exactly one invocation of recoverOrphan() means exactly one get() call.
                    countingSpanDao.getCalls.get() shouldBe 1
                } finally {
                    scope.cancel()
                    scope.coroutineContext.job.children
                        .forEach { it.join() }
                    db.close()
                }
            }
        }

        test("a second connectRealtime() after the first completes does not invoke recoverOrphan() again") {
            runBlocking {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val db = createInMemoryTestDatabase()
                try {
                    val state = SyncEngineState()
                    val sse = FakeOrphanRaceSseClient(state)
                    val catchUp = NoOpCatchUp()
                    val engine = buildOrphanTestEngine(db, state, catchUp, sse, scope)

                    val countingSpanDao = CountingTentativeSpanDao(db.tentativeSpanDao())
                    val recorder =
                        ListeningEventRecorder(
                            listeningEventDao = db.listeningEventDao(),
                            tentativeSpanDao = countingSpanDao,
                            transactionRunner = RoomTransactionRunner(db),
                            enqueue = { _, _, _ -> },
                            currentUserId = { "user-test" },
                            deviceInfo = DeviceInfoProvider { error("device info not used in this test") },
                        )

                    val authSession = mock<AuthSession> { everySuspend { getUserId() } returns "user-test" }
                    val ftsPopulator =
                        mock<FtsPopulatorContract> {
                            everySuspend { rebuildIfEmpty() } returns Unit
                            everySuspend { rebuildAll() } returns Unit
                        }
                    val scannerChannel = RpcChannel.forTest(mock<ScannerService>())

                    val repo =
                        SyncRepositoryImpl(
                            syncEngine = engine,
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

                    // First call — recovery runs once.
                    repo.connectRealtime()
                    countingSpanDao.getCalls.get() shouldBe 1

                    // Second call — recovery must not run again (flag is set).
                    repo.connectRealtime()
                    countingSpanDao.getCalls.get() shouldBe 1
                } finally {
                    scope.cancel()
                    scope.coroutineContext.job.children
                        .forEach { it.join() }
                    db.close()
                }
            }
        }
    })

// ─── Helpers ─────────────────────────────────────────────────────────────────

/**
 * Wraps a real [TentativeSpanDao] and counts [get] invocations. A call to [get] is the
 * first operation inside [ListeningEventRecorder.recoverOrphan], so counting [get] calls
 * is equivalent to counting [ListeningEventRecorder.recoverOrphan] invocations.
 */
private class CountingTentativeSpanDao(
    private val delegate: TentativeSpanDao,
) : TentativeSpanDao {
    val getCalls = AtomicInteger(0)

    override suspend fun get(): TentativeSpanEntity? {
        getCalls.incrementAndGet()
        return delegate.get()
    }

    override suspend fun upsertSingleton(span: TentativeSpanEntity) = delegate.upsertSingleton(span)

    override suspend fun delete() = delegate.delete()
}

/** Minimal [CatchUp] that succeeds immediately without doing any real sync work. */
private class NoOpCatchUp : CatchUp {
    override suspend fun <T : Any> catchUp(handler: SyncDomainHandler<T>): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun <T : Any> catchUpFromZero(handler: SyncDomainHandler<T>): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun catchUpAll(registry: ClientSyncDomainRegistry): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun <T : Any> catchUpTransient(handler: SyncDomainHandler<T>): AppResult<Set<String>> = AppResult.Success(emptySet())

    override suspend fun domains(): AppResult<List<String>> = AppResult.Success(emptyList())
}

/** Minimal SSE client that flips state to Connected on [connect]. */
private class FakeOrphanRaceSseClient(
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

private fun buildOrphanTestEngine(
    db: com.calypsan.listenup.client.data.local.db.ListenUpDatabase,
    state: SyncEngineState,
    catchUp: CatchUp,
    sse: SseClient,
    scope: CoroutineScope,
): SyncEngine {
    val registry = ClientSyncDomainRegistry()
    val store = SyncCursorStore(db.syncCursorDao())
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
    val reconciler =
        SyncReconciler(
            registry = registry,
            store = store,
            digestClient =
                DomainDigestClient(
                    httpClientProvider = { error("digest client should not be called in this test") },
                    serverUrlProvider = { "" },
                ),
            catchUp = catchUp,
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
