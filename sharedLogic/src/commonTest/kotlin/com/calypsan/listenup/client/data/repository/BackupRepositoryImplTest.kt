package com.calypsan.listenup.client.data.repository

import app.cash.turbine.test
import com.calypsan.listenup.api.BackupService
import com.calypsan.listenup.api.dto.backup.BackupEvent
import com.calypsan.listenup.api.dto.backup.BackupSummary
import com.calypsan.listenup.api.dto.backup.RestoreResult
import com.calypsan.listenup.api.error.BackupError
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.result.AppResult as WireAppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.client.data.remote.BackupRpcFactory
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BackupId
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest

/**
 * Unit tests for [BackupRepositoryImpl].
 *
 * Uses mokkery to mock [BackupService] — matching the pattern in [AuthRepositoryImplTest].
 * Each test verifies that:
 *  - suspend methods forward to the service and convert [WireAppResult] → [AppResult], and
 *  - [observeProgress] unwraps [RpcEvent.Data] into bare [BackupEvent]s while silently
 *    dropping [RpcEvent.Error] and [RpcEvent.Complete].
 */
class BackupRepositoryImplTest :
    FunSpec({

        // ── helpers ──────────────────────────────────────────────────────────────

        fun stubSummary(id: String = "bk-1") =
            BackupSummary(
                id = BackupId(id),
                createdAt = 1_000L,
                sizeBytes = 42_000L,
                includesImages = true,
                schemaVersion = "1",
                appVersion = "0.1.0",
                bookCount = 10,
                userCount = 1,
            )

        fun stubRestoreResult(id: String = "bk-1") =
            RestoreResult(
                restoredFrom = BackupId(id),
                includedImages = true,
                schemaMigratedFrom = "1",
                schemaMigratedTo = "1",
            )

        /** Minimal factory that always returns the supplied [BackupService] mock. */
        class FakeBackupRpcFactory(
            private val service: BackupService,
        ) : BackupRpcFactory {
            override suspend fun get(): BackupService = service

            override suspend fun invalidate() = Unit
        }

        fun buildRepo(service: BackupService): BackupRepositoryImpl = BackupRepositoryImpl(rpcFactory = FakeBackupRpcFactory(service))

        // ── createBackup ──────────────────────────────────────────────────────

        test("createBackup returns Success wrapping the BackupSummary on wire success") {
            runTest {
                val summary = stubSummary()
                val svc = mock<BackupService>()
                everySuspend { svc.createBackup(true) } returns WireAppResult.Success(summary)

                val result = buildRepo(svc).createBackup(includeImages = true)

                result.shouldBeInstanceOf<AppResult.Success<BackupSummary>>()
                result.data shouldBe summary
            }
        }

        test("createBackup returns Failure on wire failure") {
            runTest {
                val svc = mock<BackupService>()
                val error = BackupError.SnapshotFailed()
                everySuspend { svc.createBackup(false) } returns WireAppResult.Failure(error)

                val result = buildRepo(svc).createBackup(includeImages = false)

                result.shouldBeInstanceOf<AppResult.Failure>()
                result.error shouldBe error
            }
        }

        // ── listBackups ───────────────────────────────────────────────────────

        test("listBackups returns Success wrapping the list on wire success") {
            runTest {
                val summaries = listOf(stubSummary("bk-1"), stubSummary("bk-2"))
                val svc = mock<BackupService>()
                everySuspend { svc.listBackups() } returns WireAppResult.Success(summaries)

                val result = buildRepo(svc).listBackups()

                result.shouldBeInstanceOf<AppResult.Success<List<BackupSummary>>>()
                result.data shouldBe summaries
            }
        }

        // ── deleteBackup ──────────────────────────────────────────────────────

        test("deleteBackup returns AppResult.Success(Unit) on wire success") {
            runTest {
                val svc = mock<BackupService>()
                everySuspend { svc.deleteBackup(BackupId("bk-1")) } returns WireAppResult.Success(Unit)

                val result = buildRepo(svc).deleteBackup(BackupId("bk-1"))

                result.shouldBeInstanceOf<AppResult.Success<Unit>>()
            }
        }

        test("deleteBackup returns Failure on wire failure") {
            runTest {
                val svc = mock<BackupService>()
                val error = BackupError.BackupNotFound()
                everySuspend { svc.deleteBackup(BackupId("bk-missing")) } returns WireAppResult.Failure(error)

                val result = buildRepo(svc).deleteBackup(BackupId("bk-missing"))

                result.shouldBeInstanceOf<AppResult.Failure>()
                result.error shouldBe error
            }
        }

        // ── restoreBackup ─────────────────────────────────────────────────────

        test("restoreBackup returns Success wrapping RestoreResult on wire success") {
            runTest {
                val restoreResult = stubRestoreResult()
                val svc = mock<BackupService>()
                everySuspend { svc.restoreBackup(BackupId("bk-1")) } returns WireAppResult.Success(restoreResult)

                val result = buildRepo(svc).restoreBackup(BackupId("bk-1"))

                result.shouldBeInstanceOf<AppResult.Success<RestoreResult>>()
                result.data shouldBe restoreResult
            }
        }

        // ── observeProgress ───────────────────────────────────────────────────

        test("observeProgress unwraps RpcEvent.Data into bare BackupEvents") {
            runTest {
                val hotFlow = MutableSharedFlow<RpcEvent<BackupEvent>>()
                val svc = mock<BackupService>()
                every { svc.observeProgress() } returns hotFlow

                buildRepo(svc).observeProgress().test {
                    hotFlow.emit(RpcEvent.Data(BackupEvent.DbSnapshotting))
                    awaitItem() shouldBe BackupEvent.DbSnapshotting

                    hotFlow.emit(RpcEvent.Data(BackupEvent.Finalizing))
                    awaitItem() shouldBe BackupEvent.Finalizing

                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("observeProgress silently drops RpcEvent.Error and RpcEvent.Complete") {
            runTest {
                val progressFlow =
                    flow {
                        emit(RpcEvent.Data<BackupEvent>(BackupEvent.DbSnapshotting))
                        emit(RpcEvent.Error(InternalError()))
                        emit(RpcEvent.Complete)
                        emit(RpcEvent.Data(BackupEvent.Finalizing))
                    }

                val svc = mock<BackupService>()
                every { svc.observeProgress() } returns progressFlow

                val events = mutableListOf<BackupEvent>()
                buildRepo(svc).observeProgress().collect { events.add(it) }

                events shouldBe listOf(BackupEvent.DbSnapshotting, BackupEvent.Finalizing)
            }
        }
    })
