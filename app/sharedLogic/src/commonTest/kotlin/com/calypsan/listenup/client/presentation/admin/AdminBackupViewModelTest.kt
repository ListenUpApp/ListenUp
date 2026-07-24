package com.calypsan.listenup.client.presentation.admin

import com.calypsan.listenup.api.dto.backup.BackupEvent
import com.calypsan.listenup.api.dto.backup.BackupSummary
import com.calypsan.listenup.api.dto.backup.RestoreResult
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.BackupRepository
import com.calypsan.listenup.core.BackupId
import kotlinx.io.RawSink
import com.calypsan.listenup.core.error.ErrorBus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.io.Buffer

/**
 * Tests for [AdminBackupViewModel] backed by the RPC [BackupRepository].
 *
 * Covers loading the backup list (Loading → Ready), create-then-reload, delete,
 * and error transitions. There is no validate path — backup validation is a
 * restore-wizard concern handled by `RestoreBackupViewModel`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdminBackupViewModelTest :
    FunSpec({
        val testDispatcher = StandardTestDispatcher()

        beforeTest { Dispatchers.setMain(testDispatcher) }
        afterTest { Dispatchers.resetMain() }

        fun summary(
            id: String = "backup-1",
            createdAt: Long = 1_705_315_800_000L,
            sizeBytes: Long = 1024L * 1024L,
            includesImages: Boolean = false,
            bookCount: Int = 100,
            userCount: Int = 5,
        ) = BackupSummary(
            id = BackupId(id),
            createdAt = createdAt,
            sizeBytes = sizeBytes,
            includesImages = includesImages,
            schemaVersion = "1",
            appVersion = "1.0.0",
            bookCount = bookCount,
            userCount = userCount,
        )

        test("initial state is Loading") {
            val repo = FakeBackupRepository(listResult = AppResult.Success(emptyList()))

            val viewModel = AdminBackupViewModel(repo, errorBus = ErrorBus())

            viewModel.state.value.shouldBeInstanceOf<AdminBackupUiState.Loading>()
        }

        test("loadBackups transitions to Ready with backups sorted newest first") {
            runTest(testDispatcher) {
                val repo =
                    FakeBackupRepository(
                        listResult =
                            AppResult.Success(
                                listOf(
                                    summary(id = "older", createdAt = 1_704_067_200_000L),
                                    summary(id = "newer", createdAt = 1_705_276_800_000L),
                                    summary(id = "middle", createdAt = 1_704_844_800_000L),
                                ),
                            ),
                    )

                val viewModel = AdminBackupViewModel(repo, errorBus = ErrorBus())
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<AdminBackupUiState.Ready>()
                ready.backups shouldHaveSize 3
                ready.backups[0].id shouldBe "newer"
                ready.backups[1].id shouldBe "middle"
                ready.backups[2].id shouldBe "older"
            }
        }

        test("loadBackups handles empty list") {
            runTest(testDispatcher) {
                val repo = FakeBackupRepository(listResult = AppResult.Success(emptyList()))

                val viewModel = AdminBackupViewModel(repo, errorBus = ErrorBus())
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<AdminBackupUiState.Ready>()
                ready.backups.shouldHaveSize(0)
                ready.error.shouldBeNull()
            }
        }

        test("loadBackups initial failure transitions to Error") {
            runTest(testDispatcher) {
                val repo =
                    FakeBackupRepository(listResult = AppResult.Failure(TransportError.NetworkUnavailable()))

                val viewModel = AdminBackupViewModel(repo, errorBus = ErrorBus())
                advanceUntilIdle()

                viewModel.state.value.shouldBeInstanceOf<AdminBackupUiState.Error>()
            }
        }

        test("createBackup reloads the list on success") {
            runTest(testDispatcher) {
                val repo =
                    FakeBackupRepository(
                        listResults =
                            ArrayDeque(
                                listOf(
                                    AppResult.Success(listOf(summary(id = "initial-backup"))),
                                    AppResult.Success(listOf(summary(id = "reloaded-backup"))),
                                ),
                            ),
                        createResult = AppResult.Success(summary(id = "new-backup")),
                    )

                val viewModel = AdminBackupViewModel(repo, errorBus = ErrorBus())
                advanceUntilIdle()
                val initial = viewModel.state.value.shouldBeInstanceOf<AdminBackupUiState.Ready>()
                initial.backups[0].id shouldBe "initial-backup"

                viewModel.createBackup(includeImages = true)
                advanceUntilIdle()

                val reloaded = viewModel.state.value.shouldBeInstanceOf<AdminBackupUiState.Ready>()
                reloaded.backups[0].id shouldBe "reloaded-backup"
                reloaded.isCreating.shouldBeFalse()
                repo.createImagesArg shouldBe true
            }
        }

        test("createBackup surfaces a transient error on failure") {
            runTest(testDispatcher) {
                val repo =
                    FakeBackupRepository(
                        listResult = AppResult.Success(emptyList()),
                        createResult = AppResult.Failure(TransportError.Server4xx(statusCode = 507)),
                    )

                val viewModel = AdminBackupViewModel(repo, errorBus = ErrorBus())
                advanceUntilIdle()

                viewModel.createBackup(includeImages = false)
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<AdminBackupUiState.Ready>()
                ready.isCreating.shouldBeFalse()
                ready.error.shouldNotBeNull()
            }
        }

        test("deleteBackup removes the backup from the list") {
            runTest(testDispatcher) {
                val repo =
                    FakeBackupRepository(
                        listResult =
                            AppResult.Success(
                                listOf(summary(id = "backup-1"), summary(id = "backup-2")),
                            ),
                        deleteResult = AppResult.Success(Unit),
                    )

                val viewModel = AdminBackupViewModel(repo, errorBus = ErrorBus())
                advanceUntilIdle()
                val initial = viewModel.state.value.shouldBeInstanceOf<AdminBackupUiState.Ready>()
                initial.backups shouldHaveSize 2

                val toDelete = initial.backups.first { it.id == "backup-1" }
                viewModel.deleteBackup(toDelete)
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<AdminBackupUiState.Ready>()
                ready.backups shouldHaveSize 1
                ready.backups[0].id shouldBe "backup-2"
                ready.isDeleting.shouldBeFalse()
                repo.deletedId shouldBe BackupId("backup-1")
            }
        }

        test("deleteBackup keeps the backup and surfaces an error on failure") {
            runTest(testDispatcher) {
                val repo =
                    FakeBackupRepository(
                        listResult = AppResult.Success(listOf(summary(id = "backup-1"))),
                        deleteResult = AppResult.Failure(TransportError.Server4xx(statusCode = 409)),
                    )

                val viewModel = AdminBackupViewModel(repo, errorBus = ErrorBus())
                advanceUntilIdle()
                val initial = viewModel.state.value.shouldBeInstanceOf<AdminBackupUiState.Ready>()

                viewModel.deleteBackup(initial.backups[0])
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<AdminBackupUiState.Ready>()
                ready.isDeleting.shouldBeFalse()
                ready.error.shouldNotBeNull()
                ready.backups shouldHaveSize 1
            }
        }

        test("showDeleteConfirmation then dismiss toggles deleteConfirmBackup") {
            runTest(testDispatcher) {
                val repo = FakeBackupRepository(listResult = AppResult.Success(listOf(summary())))

                val viewModel = AdminBackupViewModel(repo, errorBus = ErrorBus())
                advanceUntilIdle()
                val initial = viewModel.state.value.shouldBeInstanceOf<AdminBackupUiState.Ready>()

                viewModel.showDeleteConfirmation(initial.backups[0])
                viewModel.state.value
                    .shouldBeInstanceOf<AdminBackupUiState.Ready>()
                    .deleteConfirmBackup
                    .shouldNotBeNull()

                viewModel.dismissDeleteConfirmation()
                viewModel.state.value
                    .shouldBeInstanceOf<AdminBackupUiState.Ready>()
                    .deleteConfirmBackup
                    .shouldBeNull()
            }
        }

        test("large backup size formats as GB") {
            runTest(testDispatcher) {
                val repo =
                    FakeBackupRepository(
                        listResult = AppResult.Success(listOf(summary(sizeBytes = 2L * 1024 * 1024 * 1024))),
                    )

                val viewModel = AdminBackupViewModel(repo, errorBus = ErrorBus())
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<AdminBackupUiState.Ready>()
                (ready.backups[0].sizeFormatted.contains("GB")).shouldBe(true)
            }
        }

        test("downloadBackup emits downloadSaved on success") {
            runTest(testDispatcher) {
                val repo =
                    FakeBackupRepository(
                        listResult = AppResult.Success(listOf(summary(id = "bk-1"))),
                        downloadResult = AppResult.Success(Unit),
                    )
                val viewModel = AdminBackupViewModel(repo, errorBus = ErrorBus())
                advanceUntilIdle()

                viewModel.downloadSaved.test {
                    viewModel.downloadBackup(BackupId("bk-1"), Buffer())
                    advanceUntilIdle()
                    awaitItem() shouldBe Unit
                }
            }
        }

        test("downloadBackup routes a failure to the error bus") {
            runTest(testDispatcher) {
                val errorBus = ErrorBus()
                val repo =
                    FakeBackupRepository(
                        listResult = AppResult.Success(listOf(summary(id = "bk-1"))),
                        downloadResult = AppResult.Failure(TransportError.NetworkUnavailable()),
                    )
                val viewModel = AdminBackupViewModel(repo, errorBus = errorBus)
                advanceUntilIdle()

                errorBus.errors.test {
                    viewModel.downloadBackup(BackupId("bk-1"), Buffer())
                    advanceUntilIdle()
                    awaitItem().shouldBeInstanceOf<TransportError.NetworkUnavailable>()
                }
            }
        }
    })

/**
 * In-memory fake of [BackupRepository] for ViewModel seam tests. Records the
 * arguments the ViewModel passes so call routing can be asserted without mocks.
 */
private class FakeBackupRepository(
    listResult: AppResult<List<BackupSummary>>? = null,
    listResults: ArrayDeque<AppResult<List<BackupSummary>>> = ArrayDeque(),
    private val createResult: AppResult<BackupSummary> = AppResult.Failure(stubError),
    private val deleteResult: AppResult<Unit> = AppResult.Success(Unit),
    private val restoreResult: AppResult<RestoreResult> = AppResult.Failure(stubError),
    private val downloadResult: AppResult<Unit> = AppResult.Success(Unit),
) : BackupRepository {
    private val listQueue = listResults.also { if (listResult != null) it.addFirst(listResult) }

    var createImagesArg: Boolean? = null
        private set
    var deletedId: BackupId? = null
        private set

    override suspend fun uploadBackup(fileSource: com.calypsan.listenup.core.FileSource): AppResult<BackupSummary> = AppResult.Failure(stubError)

    override suspend fun downloadBackup(
        id: BackupId,
        sink: RawSink,
    ): AppResult<Unit> = downloadResult

    override suspend fun createBackup(includeImages: Boolean): AppResult<BackupSummary> {
        createImagesArg = includeImages
        return createResult
    }

    override suspend fun listBackups(): AppResult<List<BackupSummary>> = if (listQueue.size > 1) listQueue.removeFirst() else listQueue.first()

    override suspend fun deleteBackup(id: BackupId): AppResult<Unit> {
        deletedId = id
        return deleteResult
    }

    override suspend fun restoreBackup(id: BackupId): AppResult<RestoreResult> = restoreResult

    override fun observeProgress(): Flow<BackupEvent> = MutableStateFlow(BackupEvent.Finalizing)

    companion object {
        private val stubError: AppError = TransportError.NetworkUnavailable()
    }
}
