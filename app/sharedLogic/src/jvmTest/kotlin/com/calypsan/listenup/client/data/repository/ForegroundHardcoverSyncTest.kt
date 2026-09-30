package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.ScannerService
import com.calypsan.listenup.api.result.AppResult
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger

/**
 * Spec B3's "a light trigger when a client comes to the foreground": every platform's foreground
 * reaches `recoverRealtime`, which nudges the server's Hardcover pull for a signed-in user — without
 * waiting on it, so a slow or absent Hardcover never holds up sync recovery.
 */
class ForegroundHardcoverSyncTest :
    FunSpec({
        fun withRepo(
            userId: String?,
            onForegrounded: suspend () -> AppResult<Unit>,
            block: suspend (SyncRepositoryImpl) -> Unit,
        ) = runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val db = createInMemoryTestDatabase()
            try {
                val state = SyncEngineState()
                val engine = buildOrphanTestEngine(db, state, NoOpCatchUp(), FakeOrphanRaceSyncStreamClient(state), scope)
                val repo =
                    SyncRepositoryImpl(
                        syncEngine = engine,
                        reevaluateConnection = {},
                        onForegrounded = onForegrounded,
                        syncEngineState = state,
                        authSession = mock<AuthSession> { everySuspend { getUserId() } returns userId },
                        listeningEventRecorder =
                            ListeningEventRecorder(
                                listeningEventDao = db.listeningEventDao(),
                                tentativeSpanDao = db.tentativeSpanDao(),
                                transactionRunner = RoomTransactionRunner(db),
                                enqueue = { _, _, _ -> },
                                currentUserId = { userId },
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
                block(repo)
            } finally {
                scope.cancel()
                scope.coroutineContext.job.children
                    .forEach { it.join() }
                db.close()
            }
        }

        test("a signed-in foreground nudges the Hardcover pull") {
            val nudged = CompletableDeferred<Unit>()
            withRepo(
                userId = "user-test",
                onForegrounded = {
                    nudged.complete(Unit)
                    AppResult.Success(Unit)
                },
            ) { repo ->
                repo.connectRealtime()
                withTimeout(5_000) { nudged.await() }
            }
        }

        test("a nudge that never answers doesn't hold up sync recovery") {
            val never = CompletableDeferred<AppResult<Unit>>()
            withRepo(userId = "user-test", onForegrounded = { never.await() }) { repo ->
                withTimeout(5_000) { repo.connectRealtime() }
            }
        }

        test("signed out, nothing is nudged") {
            val calls = AtomicInteger()
            withRepo(
                userId = null,
                onForegrounded = {
                    calls.incrementAndGet()
                    AppResult.Success(Unit)
                },
            ) { repo ->
                repo.connectRealtime()
                delay(300)
                calls.get() shouldBe 0
            }
        }
    })
