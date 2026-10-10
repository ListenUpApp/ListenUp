package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.ScannerService
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.sync.CoverPresenceReconciler
import com.calypsan.listenup.client.data.sync.FtsPopulatorContract
import com.calypsan.listenup.client.data.sync.SearchIndexWatermark
import com.calypsan.listenup.client.data.sync.SyncEngineState
import com.calypsan.listenup.client.device.DeviceInfoProvider
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.playback.ListeningEventRecorder
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The live FTS refresh observer starts once per process. If its first watermark snapshot throws, the
 * observer must not stay marked as started — otherwise live edits never reach the offline search index again
 * until the app restarts.
 */
class FtsObserverRestartTest :
    FunSpec({
        test("a failed first watermark snapshot lets the next engine start launch the observer again") {
            runBlocking {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val db = createInMemoryTestDatabase()
                try {
                    val state = SyncEngineState()
                    val engine =
                        buildOrphanTestEngine(db, state, NoOpCatchUp(), FakeOrphanRaceSyncStreamClient(state), scope)
                    var snapshotCalls = 0
                    val observerRelaunched = CompletableDeferred<Unit>()
                    val repo =
                        SyncRepositoryImpl(
                            syncEngine = engine,
                            reevaluateConnection = {},
                            onForegrounded = { AppResult.Success(Unit) },
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
                            scannerChannel = RpcChannel.forTest(mock<ScannerService>()),
                            bookDao = db.bookDao(),
                            libraryDao = db.libraryDao(),
                            listeningEventDao = db.listeningEventDao(),
                            ftsPopulator =
                                mock<FtsPopulatorContract> {
                                    everySuspend { rebuildIfEmpty() } returns Unit
                                    everySuspend { rebuildAll() } returns Unit
                                    every { observeContentChanges() } returns emptyFlow()
                                    everySuspend { snapshotWatermark() } calls {
                                        snapshotCalls++
                                        if (snapshotCalls == 1) error("snapshot failed")
                                        observerRelaunched.complete(Unit)
                                        SearchIndexWatermark(0L, 0L, 0L, 0L)
                                    }
                                },
                            coverPresenceReconciler =
                                CoverPresenceReconciler(
                                    bookDao = db.bookDao(),
                                    imageStorage = mock<ImageStorage> { every { listCoverBookIds() } returns emptySet() },
                                ),
                            scope = scope,
                        )

                    // The first start's observer dies on its snapshot; later starts must be able to relaunch it.
                    withTimeout(10_000) {
                        while (!observerRelaunched.isCompleted) {
                            repo.connectRealtime()
                            delay(50)
                        }
                    }

                    observerRelaunched.isCompleted shouldBe true
                } finally {
                    scope.cancel()
                    scope.coroutineContext.job.children
                        .forEach { it.join() }
                    db.close()
                }
            }
        }
    })
