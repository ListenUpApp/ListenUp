package com.calypsan.listenup.client.data.repository

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
import kotlinx.coroutines.runBlocking

/**
 * Whether the app WANTS the firehose open is recorded where the app decides it: connecting sets
 * it, a deliberate disconnect clears it. The reconnection supervisor recovers only a wanted
 * connection — on 2026-09-25 it read Android's leave-the-foreground disconnect as an outage and
 * kicked a reconnect every ~2 s for as long as the process lived.
 */
class RealtimeWantedTest :
    FunSpec({
        test("connecting marks the firehose wanted, and a deliberate disconnect marks it unwanted") {
            runBlocking {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val db = createInMemoryTestDatabase()
                try {
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
                            scannerChannel = RpcChannel.forTest(mock<ScannerService>()),
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
                    state.value.realtimeWanted shouldBe false

                    repo.connectRealtime()
                    state.value.realtimeWanted shouldBe true

                    repo.disconnect()
                    state.value.realtimeWanted shouldBe false

                    // Coming back to the foreground wants it again.
                    repo.connectRealtime()
                    state.value.realtimeWanted shouldBe true
                } finally {
                    scope.cancel()
                    scope.coroutineContext.job.children
                        .forEach { it.join() }
                    db.close()
                }
            }
        }
    })
