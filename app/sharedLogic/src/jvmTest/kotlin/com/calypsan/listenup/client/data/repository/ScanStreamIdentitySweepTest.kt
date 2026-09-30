package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.ScannerService
import com.calypsan.listenup.api.event.ScanEvent
import com.calypsan.listenup.api.dto.scan.ScanIssue
import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.dto.scanner.ScanResultSummary
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.RpcConnection
import com.calypsan.listenup.client.data.remote.RpcPolicy
import com.calypsan.listenup.client.data.remote.RpcProxyCache
import com.calypsan.listenup.client.data.sync.ConnectionState
import com.calypsan.listenup.client.data.sync.CoverPresenceReconciler
import com.calypsan.listenup.client.data.sync.FtsPopulatorContract
import com.calypsan.listenup.client.data.sync.SearchIndexWatermark
import com.calypsan.listenup.client.data.sync.SyncEngineState
import com.calypsan.listenup.client.device.DeviceInfoProvider
import com.calypsan.listenup.client.di.e2e.TestServerConfig
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
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The scan-progress observer runs for the life of the process on an app-wide scope, and nothing
 * cancels it on logout. So when the identity behind the connection changes — logout, re-login as
 * someone else, a new server — the identity sweep must CLOSE the connection it rides, not merely
 * retire it: a retired connection closes only once nothing uses it, and this stream never stops
 * using it, so the old user's authed socket would outlive the logout and speak for the next user.
 *
 * Closed, the stream surfaces an error, and the observer's own loop waits for the engine to be
 * connected again before it resubscribes — on a fresh connection.
 *
 * `runBlocking` on a real dispatcher: the repository's scope is a real multi-threaded one, and the
 * observer's resubscribe delay is real time.
 */
class ScanStreamIdentitySweepTest :
    FunSpec({

        /**
         * A scanner whose progress stream is silent until its connection is closed, then fails the way
         * kotlinx.rpc fails a live request when its client is closed: a bare "Client cancelled".
         */
        class SilentScanner(
            private val connectionClosed: CompletableDeferred<Unit>,
            private val onSubscribe: () -> Unit,
        ) : ScannerService {
            override fun observeProgress(): Flow<RpcEvent<ScanEvent>> =
                flow {
                    onSubscribe()
                    connectionClosed.await()
                    throw CancellationException("Client cancelled")
                }

            override suspend fun scanFull(): AppResult<ScanResultSummary> = error("not used")

            override suspend fun lastScanResult(): AppResult<ScanResult> = error("not used")

            override suspend fun listScanIssues(): AppResult<List<ScanIssue>> = error("not used")

            override suspend fun dismissScanIssue(issueId: String): AppResult<Unit> = error("not used")
        }

        suspend fun awaitUntil(
            within: Duration = 10.seconds,
            condition: () -> Boolean,
        ) {
            val deadline = TimeSource.Monotonic.markNow() + within
            while (!condition() && deadline.hasNotPassedNow()) delay(20)
        }

        test("an identity sweep closes the scan stream's connection, and it resubscribes fresh once connected") {
            runBlocking {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val db = createInMemoryTestDatabase()
                try {
                    val connectionsClosed = CopyOnWriteArrayList<CompletableDeferred<Unit>>()
                    val subscriptions = CopyOnWriteArrayList<Int>()
                    val scannerCache =
                        RpcProxyCache<ScannerService>(
                            apiClientFactory =
                                mock<ApiClientFactory> {
                                    everySuspend { getClient() } returns
                                        HttpClient(MockEngine { respond("") }) { install(WebSockets) }
                                },
                            serverConfig = TestServerConfig("http://127.0.0.1:1"),
                        ) { _, _ ->
                            val id = connectionsClosed.size
                            val closed = CompletableDeferred<Unit>()
                            connectionsClosed += closed
                            RpcConnection<ScannerService>(SilentScanner(closed) { subscriptions += id }) { closed.complete(Unit) }
                        }

                    val state = SyncEngineState()
                    val engine =
                        buildOrphanTestEngine(db, state, NoOpCatchUp(), FakeOrphanRaceSyncStreamClient(state), scope)
                    val repo =
                        SyncRepositoryImpl(
                            syncEngine = engine,
                            reevaluateConnection = {},
                            syncEngineState = state,
                            authSession = mock<AuthSession> { everySuspend { getUserId() } returns "user-test" },
                            listeningEventRecorder =
                                ListeningEventRecorder(
                                    listeningEventDao = db.listeningEventDao(),
                                    tentativeSpanDao = db.tentativeSpanDao(),
                                    transactionRunner = RoomTransactionRunner(db),
                                    enqueue = { _, _, _ -> },
                                    currentUserId = { "user-test" },
                                    deviceInfo = DeviceInfoProvider { error("device info not used in this test") },
                                ),
                            scannerChannel = RpcChannel(scannerCache, RpcPolicy.Authed),
                            bookDao = db.bookDao(),
                            libraryDao = db.libraryDao(),
                            listeningEventDao = db.listeningEventDao(),
                            ftsPopulator =
                                mock<FtsPopulatorContract> {
                                    everySuspend { rebuildIfEmpty() } returns Unit
                                    everySuspend { rebuildAll() } returns Unit
                                    every { observeContentChanges() } returns emptyFlow()
                                    everySuspend { snapshotWatermark() } returns SearchIndexWatermark(0L, 0L, 0L, 0L)
                                },
                            coverPresenceReconciler =
                                CoverPresenceReconciler(
                                    bookDao = db.bookDao(),
                                    imageStorage = mock<ImageStorage> { every { listCoverBookIds() } returns emptySet() },
                                ),
                            scope = scope,
                        )

                    repo.connectRealtime()
                    awaitUntil { subscriptions.size == 1 }
                    subscriptions.toList() shouldBe listOf(0)

                    // Logout: the firehose drops, then the identity sweep runs over every channel.
                    state.setConnection(ConnectionState.Disconnected(reason = "logout"))
                    scannerCache.invalidate()

                    // The stream's connection is closed even though the stream was still riding it…
                    connectionsClosed[0].isCompleted shouldBe true
                    // …and while nobody is signed in, the observer waits instead of resubscribing.
                    delay(3.seconds)
                    subscriptions.toList() shouldBe listOf(0)

                    // Signed back in: the observer resubscribes, on a fresh connection.
                    state.setConnection(ConnectionState.Connected(lastEventId = null))
                    awaitUntil { subscriptions.size == 2 }
                    subscriptions.toList() shouldBe listOf(0, 1)
                } finally {
                    scope.cancel()
                    scope.coroutineContext.job.children
                        .forEach { it.join() }
                    db.close()
                }
            }
        }
    })
