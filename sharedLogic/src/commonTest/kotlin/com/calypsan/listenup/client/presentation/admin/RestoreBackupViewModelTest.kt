package com.calypsan.listenup.client.presentation.admin

import com.calypsan.listenup.api.dto.backup.BackupEvent
import com.calypsan.listenup.api.dto.backup.BackupSummary
import com.calypsan.listenup.api.dto.backup.RestoreResult
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.BackupRepository
import com.calypsan.listenup.core.BackupId
import com.calypsan.listenup.core.error.ErrorBus
import app.cash.turbine.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Tests for [RestoreBackupViewModel] backed by the RPC [BackupRepository].
 *
 * The restore wizard collapses to the server's id-only contract:
 * pick (preselected via [backupId]) → client-side destructive confirmation →
 * `restoreBackup(id)` → render the [RestoreResult]. There is no validate step
 * and no restore options (mode/mergeStrategy/dryRun/confirmFullWipe) — those
 * REST-era concepts are gone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RestoreBackupViewModelTest :
    FunSpec({
        val testDispatcher = StandardTestDispatcher()

        beforeTest { Dispatchers.setMain(testDispatcher) }
        afterTest { Dispatchers.resetMain() }

        fun restoreResult(
            from: String = "backup-1",
            includedImages: Boolean = true,
            migratedFrom: String = "3",
            migratedTo: String = "5",
        ) = RestoreResult(
            restoredFrom = BackupId(from),
            includedImages = includedImages,
            schemaMigratedFrom = migratedFrom,
            schemaMigratedTo = migratedTo,
        )

        test("initial state is Idle, awaiting confirmation") {
            val repo = FakeRestoreBackupRepository()

            val viewModel = RestoreBackupViewModel("backup-1", repo, errorBus = ErrorBus())

            viewModel.state.value.shouldBeInstanceOf<RestoreBackupUiState.Idle>()
        }

        test("requestRestore moves to Confirming") {
            val repo = FakeRestoreBackupRepository()

            val viewModel = RestoreBackupViewModel("backup-1", repo, errorBus = ErrorBus())
            viewModel.requestRestore()

            viewModel.state.value.shouldBeInstanceOf<RestoreBackupUiState.Confirming>()
        }

        test("cancelRestore returns from Confirming to Idle") {
            val repo = FakeRestoreBackupRepository()

            val viewModel = RestoreBackupViewModel("backup-1", repo, errorBus = ErrorBus())
            viewModel.requestRestore()
            viewModel.cancelRestore()

            viewModel.state.value.shouldBeInstanceOf<RestoreBackupUiState.Idle>()
        }

        test("confirmRestore calls restoreBackup with the id and completes with the result") {
            runTest(testDispatcher) {
                val repo =
                    FakeRestoreBackupRepository(
                        restoreResult = AppResult.Success(restoreResult(from = "backup-1")),
                    )

                val viewModel = RestoreBackupViewModel("backup-1", repo, errorBus = ErrorBus())
                viewModel.requestRestore()
                viewModel.confirmRestore()
                advanceUntilIdle()

                repo.restoredId shouldBe BackupId("backup-1")
                val completed = viewModel.state.value.shouldBeInstanceOf<RestoreBackupUiState.Completed>()
                completed.result.restoredFrom shouldBe BackupId("backup-1")
                completed.result.schemaMigratedTo shouldBe "5"
            }
        }

        test("confirmRestore failure returns to Idle with a transient error") {
            runTest(testDispatcher) {
                val repo =
                    FakeRestoreBackupRepository(
                        restoreResult = AppResult.Failure(TransportError.Server4xx(statusCode = 503)),
                    )

                val viewModel = RestoreBackupViewModel("backup-1", repo, errorBus = ErrorBus())
                viewModel.requestRestore()
                viewModel.confirmRestore()
                advanceUntilIdle()

                val idle = viewModel.state.value.shouldBeInstanceOf<RestoreBackupUiState.Idle>()
                idle.error.shouldNotBeNull()
            }
        }

        test("confirmRestore does nothing when not Confirming") {
            runTest(testDispatcher) {
                val repo = FakeRestoreBackupRepository(restoreResult = AppResult.Success(restoreResult()))

                val viewModel = RestoreBackupViewModel("backup-1", repo, errorBus = ErrorBus())
                // No requestRestore() first.
                viewModel.confirmRestore()
                advanceUntilIdle()

                viewModel.state.value.shouldBeInstanceOf<RestoreBackupUiState.Idle>()
                repo.restoredId shouldBe null
            }
        }

        test("progress reflects restore events from the repository stream") {
            runTest(testDispatcher) {
                val repo =
                    FakeRestoreBackupRepository(
                        progressEvents = flowOf(BackupEvent.Draining, BackupEvent.Swapping, BackupEvent.Migrating),
                    )

                val viewModel = RestoreBackupViewModel("backup-1", repo, errorBus = ErrorBus())

                // WhileSubscribed: upstream collects only while observed. Turbine subscribes,
                // so the terminal Migrating event is observed (intermediate events may be
                // conflated by the StateFlow).
                viewModel.progress.test {
                    var latest = awaitItem()
                    while (latest != BackupEvent.Migrating) {
                        latest = awaitItem()
                    }
                    latest shouldBe BackupEvent.Migrating
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })

/**
 * In-memory fake of [BackupRepository] for ViewModel seam tests. Records the id
 * the ViewModel passes to [restoreBackup] so call routing can be asserted without
 * mocks.
 */
private class FakeRestoreBackupRepository(
    private val restoreResult: AppResult<RestoreResult> = AppResult.Failure(stubError),
    private val progressEvents: Flow<BackupEvent> = MutableStateFlow(BackupEvent.Draining),
) : BackupRepository {
    var restoredId: BackupId? = null
        private set

    override suspend fun createBackup(includeImages: Boolean): AppResult<BackupSummary> = AppResult.Failure(stubError)

    override suspend fun listBackups(): AppResult<List<BackupSummary>> = AppResult.Success(emptyList())

    override suspend fun deleteBackup(id: BackupId): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun restoreBackup(id: BackupId): AppResult<RestoreResult> {
        restoredId = id
        return restoreResult
    }

    override fun observeProgress(): Flow<BackupEvent> = progressEvents

    companion object {
        private val stubError: AppError = TransportError.NetworkUnavailable()
    }
}
