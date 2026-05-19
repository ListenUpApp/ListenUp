package com.calypsan.listenup.client.presentation.admin

import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.client.data.remote.BackupApiContract
import com.calypsan.listenup.client.data.remote.model.RestoreError
import com.calypsan.listenup.client.data.remote.model.RestoreRequest
import com.calypsan.listenup.client.data.remote.model.RestoreResponse
import com.calypsan.listenup.client.data.remote.model.ValidationResponse
import com.calypsan.listenup.client.domain.repository.SyncRepository
import dev.mokkery.answering.returns
import dev.mokkery.answering.sequentiallyReturns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.calypsan.listenup.core.error.ErrorBus

/**
 * Tests for RestoreBackupViewModel.
 *
 * Covers:
 * - Wizard flow navigation (forward/back)
 * - Mode selection (Fresh vs Merge)
 * - Merge strategy selection
 * - Dry run execution
 * - Actual restore execution
 * - Error handling at each step
 * - State transitions
 * - Edge cases (partial failures, cancellation, etc.)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RestoreBackupViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private fun createMockSyncRepository(): SyncRepository {
        val syncRepo: SyncRepository = mock()
        everySuspend { syncRepo.forceFullResync() } returns AppResult.Success(Unit)
        everySuspend { syncRepo.refreshListeningHistory() } returns AppResult.Success(Unit)
        return syncRepo
    }

    private fun createValidationResponse(
        valid: Boolean = true,
        version: String = "1.0",
        serverName: String = "Test Server",
        expectedCounts: Map<String, Int> =
            mapOf(
                "users" to 5,
                "books" to 100,
                "contributors" to 50,
                "series" to 20,
            ),
        errors: List<String> = emptyList(),
        warnings: List<String> = emptyList(),
    ) = ValidationResponse(
        valid = valid,
        version = version,
        serverName = serverName,
        expectedCounts = expectedCounts,
        errors = errors,
        warnings = warnings,
    )

    private fun createRestoreResponse(
        imported: Map<String, Int> =
            mapOf(
                "users" to 5,
                "books" to 100,
            ),
        skipped: Map<String, Int> = emptyMap(),
        errors: List<RestoreError> = emptyList(),
        duration: String = "2.5s",
    ) = RestoreResponse(
        imported = imported,
        skipped = skipped,
        errors = errors,
        duration = duration,
    )

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ========== Initial State ==========

    @Test
    fun `initial state is Loading`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.validateBackup("backup-1") } returns
                AppResult.Success(createValidationResponse())

            val viewModel = RestoreBackupViewModel("backup-1", api, createMockSyncRepository(), errorBus = ErrorBus())

            assertIs<RestoreBackupUiState.Loading>(viewModel.state.value)
        }

    @Test
    fun `init transitions to Ready with validation after success`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.validateBackup("backup-1") } returns
                AppResult.Success(
                    createValidationResponse(
                        valid = true,
                        serverName = "My Server",
                    ),
                )

            val viewModel = RestoreBackupViewModel("backup-1", api, createMockSyncRepository(), errorBus = ErrorBus())
            advanceUntilIdle()

            val ready = assertIs<RestoreBackupUiState.Ready>(viewModel.state.value)
            assertEquals("backup-1", ready.backupId)
            assertEquals(RestoreStep.MODE_SELECTION, ready.step)
            assertNull(ready.mode)
            assertNull(ready.mergeStrategy)
            assertFalse(ready.isValidating)
            val validation = assertNotNull(ready.validation)
            assertEquals("My Server", validation.serverName)
        }

    @Test
    fun `init transitions to Error when initial validation fails`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.validateBackup("backup-1") } returns
                AppResult.Failure(TransportError.NetworkUnavailable())

            val viewModel = RestoreBackupViewModel("backup-1", api, createMockSyncRepository(), errorBus = ErrorBus())
            advanceUntilIdle()

            assertIs<RestoreBackupUiState.Error>(viewModel.state.value)
        }

    // ========== Mode Selection ==========

    @Test
    fun `selectMode updates mode in state`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.validateBackup(any()) } returns
                AppResult.Success(createValidationResponse())

            val viewModel = RestoreBackupViewModel("backup-1", api, createMockSyncRepository(), errorBus = ErrorBus())
            advanceUntilIdle()

            viewModel.selectMode(RestoreMode.FRESH)
            assertEquals(RestoreMode.FRESH, assertIs<RestoreBackupUiState.Ready>(viewModel.state.value).mode)

            viewModel.selectMode(RestoreMode.MERGE)
            assertEquals(RestoreMode.MERGE, assertIs<RestoreBackupUiState.Ready>(viewModel.state.value).mode)
        }

    @Test
    fun `selectMode clears any previous transient error`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.validateBackup(any()) } returns
                AppResult.Success(createValidationResponse())
            // Use dry run failure to produce a transient error on Ready.
            everySuspend { api.restore(any()) } returns
                AppResult.Failure(TransportError.Server4xx(statusCode = 500))

            val viewModel = RestoreBackupViewModel("backup-1", api, createMockSyncRepository(), errorBus = ErrorBus())
            advanceUntilIdle()

            viewModel.selectMergeStrategy(MergeStrategy.KEEP_LOCAL)
            viewModel.performDryRun()
            advanceUntilIdle()
            assertNotNull(assertIs<RestoreBackupUiState.Ready>(viewModel.state.value).error)

            viewModel.selectMode(RestoreMode.FRESH)
            assertNull(assertIs<RestoreBackupUiState.Ready>(viewModel.state.value).error)
        }

    // ========== Merge Strategy Selection ==========

    @Test
    fun `selectMergeStrategy updates strategy in state`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.validateBackup(any()) } returns
                AppResult.Success(createValidationResponse())

            val viewModel = RestoreBackupViewModel("backup-1", api, createMockSyncRepository(), errorBus = ErrorBus())
            advanceUntilIdle()

            viewModel.selectMergeStrategy(MergeStrategy.KEEP_LOCAL)
            assertEquals(
                MergeStrategy.KEEP_LOCAL,
                assertIs<RestoreBackupUiState.Ready>(viewModel.state.value).mergeStrategy,
            )

            viewModel.selectMergeStrategy(MergeStrategy.KEEP_BACKUP)
            assertEquals(
                MergeStrategy.KEEP_BACKUP,
                assertIs<RestoreBackupUiState.Ready>(viewModel.state.value).mergeStrategy,
            )

            viewModel.selectMergeStrategy(MergeStrategy.NEWEST)
            assertEquals(
                MergeStrategy.NEWEST,
                assertIs<RestoreBackupUiState.Ready>(viewModel.state.value).mergeStrategy,
            )
        }

    // ========== Wizard Navigation - Forward ==========

    @Test
    fun `nextStep from MODE_SELECTION with FRESH mode goes to VALIDATION`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.validateBackup(any()) } returns
                AppResult.Success(createValidationResponse())

            val viewModel = RestoreBackupViewModel("backup-1", api, createMockSyncRepository(), errorBus = ErrorBus())
            advanceUntilIdle()

            viewModel.selectMode(RestoreMode.FRESH)
            viewModel.nextStep()

            assertEquals(
                RestoreStep.VALIDATION,
                assertIs<RestoreBackupUiState.Ready>(viewModel.state.value).step,
            )
        }

    @Test
    fun `nextStep from MODE_SELECTION with MERGE mode goes to MERGE_STRATEGY`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.validateBackup(any()) } returns
                AppResult.Success(createValidationResponse())

            val viewModel = RestoreBackupViewModel("backup-1", api, createMockSyncRepository(), errorBus = ErrorBus())
            advanceUntilIdle()

            viewModel.selectMode(RestoreMode.MERGE)
            viewModel.nextStep()

            assertEquals(
                RestoreStep.MERGE_STRATEGY,
                assertIs<RestoreBackupUiState.Ready>(viewModel.state.value).step,
            )
        }

    @Test
    fun `nextStep from MERGE_STRATEGY goes to VALIDATION`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.validateBackup(any()) } returns
                AppResult.Success(createValidationResponse())

            val viewModel = RestoreBackupViewModel("backup-1", api, createMockSyncRepository(), errorBus = ErrorBus())
            advanceUntilIdle()

            viewModel.selectMode(RestoreMode.MERGE)
            viewModel.nextStep() // MODE_SELECTION -> MERGE_STRATEGY

            viewModel.selectMergeStrategy(MergeStrategy.NEWEST)
            viewModel.nextStep() // MERGE_STRATEGY -> VALIDATION

            assertEquals(
                RestoreStep.VALIDATION,
                assertIs<RestoreBackupUiState.Ready>(viewModel.state.value).step,
            )
        }

    @Test
    fun `nextStep from VALIDATION goes to CONFIRMATION`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.validateBackup(any()) } returns
                AppResult.Success(createValidationResponse())

            val viewModel = RestoreBackupViewModel("backup-1", api, createMockSyncRepository(), errorBus = ErrorBus())
            advanceUntilIdle()

            viewModel.selectMode(RestoreMode.FRESH)
            viewModel.nextStep() // -> VALIDATION
            viewModel.nextStep() // -> CONFIRMATION

            assertEquals(
                RestoreStep.CONFIRMATION,
                assertIs<RestoreBackupUiState.Ready>(viewModel.state.value).step,
            )
        }

    @Test
    fun `nextStep from CONFIRMATION triggers restore`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.validateBackup(any()) } returns
                AppResult.Success(createValidationResponse())
            everySuspend { api.restore(any()) } returns AppResult.Success(createRestoreResponse())

            val viewModel = RestoreBackupViewModel("backup-1", api, createMockSyncRepository(), errorBus = ErrorBus())
            advanceUntilIdle()

            viewModel.selectMode(RestoreMode.FRESH)
            viewModel.nextStep() // -> VALIDATION
            viewModel.nextStep() // -> CONFIRMATION
            viewModel.nextStep() // -> RESTORING (triggers restore)
            advanceUntilIdle() // Let restore complete

            // After restore completes, should be at RESULTS
            val ready = assertIs<RestoreBackupUiState.Ready>(viewModel.state.value)
            assertEquals(RestoreStep.RESULTS, ready.step)
            assertFalse(ready.isRestoring)
            verifySuspend { api.restore(any()) }
        }

    @Test
    fun `restore completes and goes to RESULTS`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.validateBackup(any()) } returns
                AppResult.Success(createValidationResponse())
            everySuspend { api.restore(any()) } returns
                AppResult.Success(
                    createRestoreResponse(
                        imported = mapOf("books" to 50, "users" to 3),
                        skipped = mapOf("books" to 10),
                        duration = "5.2s",
                    ),
                )

            val viewModel = RestoreBackupViewModel("backup-1", api, createMockSyncRepository(), errorBus = ErrorBus())
            advanceUntilIdle()

            viewModel.selectMode(RestoreMode.FRESH)
            viewModel.nextStep() // -> VALIDATION
            viewModel.nextStep() // -> CONFIRMATION
            viewModel.nextStep() // -> RESTORING
            advanceUntilIdle()

            val ready = assertIs<RestoreBackupUiState.Ready>(viewModel.state.value)
            assertEquals(RestoreStep.RESULTS, ready.step)
            assertFalse(ready.isRestoring)
            val results = assertNotNull(ready.restoreResults)
            assertEquals(50, results.imported["books"])
            assertEquals(10, results.skipped["books"])
            assertEquals("5.2s", results.duration)
        }

    // ========== Wizard Navigation - Back ==========

    @Test
    fun `previousStep from MODE_SELECTION stays at MODE_SELECTION`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.validateBackup(any()) } returns
                AppResult.Success(createValidationResponse())

            val viewModel = RestoreBackupViewModel("backup-1", api, createMockSyncRepository(), errorBus = ErrorBus())
            advanceUntilIdle()

            viewModel.previousStep()
            assertEquals(
                RestoreStep.MODE_SELECTION,
                assertIs<RestoreBackupUiState.Ready>(viewModel.state.value).step,
            )
        }

    @Test
    fun `previousStep from MERGE_STRATEGY goes to MODE_SELECTION`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.validateBackup(any()) } returns
                AppResult.Success(createValidationResponse())

            val viewModel = RestoreBackupViewModel("backup-1", api, createMockSyncRepository(), errorBus = ErrorBus())
            advanceUntilIdle()

            viewModel.selectMode(RestoreMode.MERGE)
            viewModel.nextStep() // -> MERGE_STRATEGY
            assertEquals(
                RestoreStep.MERGE_STRATEGY,
                assertIs<RestoreBackupUiState.Ready>(viewModel.state.value).step,
            )

            viewModel.previousStep()
            assertEquals(
                RestoreStep.MODE_SELECTION,
                assertIs<RestoreBackupUiState.Ready>(viewModel.state.value).step,
            )
        }

    @Test
    fun `previousStep from VALIDATION with FRESH mode goes to MODE_SELECTION`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.validateBackup(any()) } returns
                AppResult.Success(createValidationResponse())

            val viewModel = RestoreBackupViewModel("backup-1", api, createMockSyncRepository(), errorBus = ErrorBus())
            advanceUntilIdle()

            viewModel.selectMode(RestoreMode.FRESH)
            viewModel.nextStep() // -> VALIDATION

            viewModel.previousStep()
            assertEquals(
                RestoreStep.MODE_SELECTION,
                assertIs<RestoreBackupUiState.Ready>(viewModel.state.value).step,
            )
        }

    @Test
    fun `previousStep from VALIDATION with MERGE mode goes to MERGE_STRATEGY`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.validateBackup(any()) } returns
                AppResult.Success(createValidationResponse())

            val viewModel = RestoreBackupViewModel("backup-1", api, createMockSyncRepository(), errorBus = ErrorBus())
            advanceUntilIdle()

            viewModel.selectMode(RestoreMode.MERGE)
            viewModel.nextStep() // -> MERGE_STRATEGY
            viewModel.selectMergeStrategy(MergeStrategy.KEEP_LOCAL)
            viewModel.nextStep() // -> VALIDATION

            viewModel.previousStep()
            assertEquals(
                RestoreStep.MERGE_STRATEGY,
                assertIs<RestoreBackupUiState.Ready>(viewModel.state.value).step,
            )
        }

    @Test
    fun `previousStep from CONFIRMATION goes to VALIDATION`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.validateBackup(any()) } returns
                AppResult.Success(createValidationResponse())

            val viewModel = RestoreBackupViewModel("backup-1", api, createMockSyncRepository(), errorBus = ErrorBus())
            advanceUntilIdle()

            viewModel.selectMode(RestoreMode.FRESH)
            viewModel.nextStep() // -> VALIDATION
            viewModel.nextStep() // -> CONFIRMATION

            viewModel.previousStep()
            assertEquals(
                RestoreStep.VALIDATION,
                assertIs<RestoreBackupUiState.Ready>(viewModel.state.value).step,
            )
        }

    @Test
    fun `previousStep from RESTORING stays at RESTORING - cannot go back`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.validateBackup(any()) } returns
                AppResult.Success(createValidationResponse())
            // Make restore hang by not completing
            everySuspend { api.restore(any()) } returns AppResult.Success(createRestoreResponse())

            val viewModel = RestoreBackupViewModel("backup-1", api, createMockSyncRepository(), errorBus = ErrorBus())
            advanceUntilIdle()

            viewModel.selectMode(RestoreMode.FRESH)
            viewModel.nextStep() // -> VALIDATION
            viewModel.nextStep() // -> CONFIRMATION
            viewModel.nextStep() // -> RESTORING

            viewModel.previousStep()
            // Should still be at RESTORING - can't go back during restore
            assertEquals(
                RestoreStep.RESTORING,
                assertIs<RestoreBackupUiState.Ready>(viewModel.state.value).step,
            )
        }

    @Test
    fun `previousStep from RESULTS stays at RESULTS - cannot go back`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.validateBackup(any()) } returns
                AppResult.Success(createValidationResponse())
            everySuspend { api.restore(any()) } returns AppResult.Success(createRestoreResponse())

            val viewModel = RestoreBackupViewModel("backup-1", api, createMockSyncRepository(), errorBus = ErrorBus())
            advanceUntilIdle()

            viewModel.selectMode(RestoreMode.FRESH)
            viewModel.nextStep() // -> VALIDATION
            viewModel.nextStep() // -> CONFIRMATION
            viewModel.nextStep() // -> RESTORING
            advanceUntilIdle() // -> RESULTS

            viewModel.previousStep()
            assertEquals(
                RestoreStep.RESULTS,
                assertIs<RestoreBackupUiState.Ready>(viewModel.state.value).step,
            )
        }

    // ========== Dry Run ==========

    @Test
    fun `performDryRun executes with dryRun flag true`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.validateBackup(any()) } returns
                AppResult.Success(createValidationResponse())
            everySuspend { api.restore(any()) } returns
                AppResult.Success(
                    createRestoreResponse(
                        imported = mapOf("books" to 100),
                        skipped = mapOf("users" to 2),
                        duration = "0.5s",
                    ),
                )

            val viewModel = RestoreBackupViewModel("backup-1", api, createMockSyncRepository(), errorBus = ErrorBus())
            advanceUntilIdle()

            viewModel.selectMode(RestoreMode.MERGE)
            viewModel.selectMergeStrategy(MergeStrategy.KEEP_LOCAL)
            viewModel.performDryRun()
            advanceUntilIdle()

            // Verify dry run was called with correct parameters
            verifySuspend {
                api.restore(
                    RestoreRequest(
                        backupId = "backup-1",
                        mode = "merge",
                        mergeStrategy = "keep_local",
                        dryRun = true,
                        confirmFullWipe = false,
                    ),
                )
            }

            val ready = assertIs<RestoreBackupUiState.Ready>(viewModel.state.value)
            assertFalse(ready.isValidating)
            val dryRun = assertNotNull(ready.dryRunResults)
            assertEquals(100, dryRun.willImport["books"])
            assertEquals(2, dryRun.willSkip["users"])
        }

    @Test
    fun `performDryRun handles errors`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.validateBackup(any()) } returns
                AppResult.Success(createValidationResponse())
            everySuspend { api.restore(any()) } returns
                AppResult.Failure(TransportError.Server4xx(statusCode = 500))

            val viewModel = RestoreBackupViewModel("backup-1", api, createMockSyncRepository(), errorBus = ErrorBus())
            advanceUntilIdle()

            viewModel.selectMode(RestoreMode.MERGE)
            viewModel.selectMergeStrategy(MergeStrategy.KEEP_LOCAL)
            viewModel.performDryRun()
            advanceUntilIdle()

            val ready = assertIs<RestoreBackupUiState.Ready>(viewModel.state.value)
            assertFalse(ready.isValidating)
            assertNull(ready.dryRunResults)
            assertNotNull(ready.error)
        }

    // ========== Actual Restore ==========

    @Test
    fun `restore with FRESH mode sends confirmFullWipe true`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.validateBackup(any()) } returns
                AppResult.Success(createValidationResponse())
            everySuspend { api.restore(any()) } returns AppResult.Success(createRestoreResponse())

            val viewModel = RestoreBackupViewModel("backup-1", api, createMockSyncRepository(), errorBus = ErrorBus())
            advanceUntilIdle()

            viewModel.selectMode(RestoreMode.FRESH)
            viewModel.nextStep() // -> VALIDATION
            viewModel.nextStep() // -> CONFIRMATION
            viewModel.nextStep() // -> RESTORING (triggers restore)
            advanceUntilIdle()

            verifySuspend {
                api.restore(
                    RestoreRequest(
                        backupId = "backup-1",
                        mode = "full",
                        mergeStrategy = null,
                        dryRun = false,
                        confirmFullWipe = true,
                    ),
                )
            }
        }

    @Test
    fun `restore with MERGE mode sends mergeStrategy`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.validateBackup(any()) } returns
                AppResult.Success(createValidationResponse())
            everySuspend { api.restore(any()) } returns AppResult.Success(createRestoreResponse())

            val viewModel = RestoreBackupViewModel("backup-1", api, createMockSyncRepository(), errorBus = ErrorBus())
            advanceUntilIdle()

            viewModel.selectMode(RestoreMode.MERGE)
            viewModel.nextStep() // -> MERGE_STRATEGY
            viewModel.selectMergeStrategy(MergeStrategy.NEWEST)
            viewModel.nextStep() // -> VALIDATION
            viewModel.nextStep() // -> CONFIRMATION
            viewModel.nextStep() // -> RESTORING
            advanceUntilIdle()

            verifySuspend {
                api.restore(
                    RestoreRequest(
                        backupId = "backup-1",
                        mode = "merge",
                        mergeStrategy = "newest",
                        dryRun = false,
                        confirmFullWipe = false,
                    ),
                )
            }
        }

    @Test
    fun `restore handles partial failures with errors in response`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.validateBackup(any()) } returns
                AppResult.Success(createValidationResponse())
            everySuspend { api.restore(any()) } returns
                AppResult.Success(
                    createRestoreResponse(
                        imported = mapOf("books" to 98),
                        skipped = mapOf("books" to 2),
                        errors =
                            listOf(
                                RestoreError(entityType = "book", entityId = "book-1", error = "Invalid format"),
                                RestoreError(entityType = "book", entityId = "book-2", error = "Missing field"),
                            ),
                        duration = "3.0s",
                    ),
                )

            val viewModel = RestoreBackupViewModel("backup-1", api, createMockSyncRepository(), errorBus = ErrorBus())
            advanceUntilIdle()

            viewModel.selectMode(RestoreMode.FRESH)
            viewModel.nextStep()
            viewModel.nextStep()
            viewModel.nextStep()
            advanceUntilIdle()

            val ready = assertIs<RestoreBackupUiState.Ready>(viewModel.state.value)
            val results = assertNotNull(ready.restoreResults)
            assertEquals(98, results.imported["books"])
            assertEquals(2, results.errors.size)
            assertEquals("book-1", results.errors[0].entityId)
        }

    @Test
    fun `restore handles complete failure`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.validateBackup(any()) } returns
                AppResult.Success(createValidationResponse())
            everySuspend { api.restore(any()) } returns
                AppResult.Failure(TransportError.Server4xx(statusCode = 503))

            val viewModel = RestoreBackupViewModel("backup-1", api, createMockSyncRepository(), errorBus = ErrorBus())
            advanceUntilIdle()

            viewModel.selectMode(RestoreMode.FRESH)
            viewModel.nextStep()
            viewModel.nextStep()
            viewModel.nextStep()
            advanceUntilIdle()

            val ready = assertIs<RestoreBackupUiState.Ready>(viewModel.state.value)
            assertFalse(ready.isRestoring)
            assertNull(ready.restoreResults)
            assertNotNull(ready.error)
            // Should still be at RESTORING step (not advance to RESULTS)
            assertEquals(RestoreStep.RESTORING, ready.step)
        }

    // ========== Merge Strategies ==========

    @Test
    fun `KEEP_LOCAL strategy uses keep_local API value`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.validateBackup(any()) } returns
                AppResult.Success(createValidationResponse())
            everySuspend { api.restore(any()) } returns AppResult.Success(createRestoreResponse())

            val viewModel = RestoreBackupViewModel("backup-1", api, createMockSyncRepository(), errorBus = ErrorBus())
            advanceUntilIdle()

            viewModel.selectMode(RestoreMode.MERGE)
            viewModel.nextStep() // -> MERGE_STRATEGY
            viewModel.selectMergeStrategy(MergeStrategy.KEEP_LOCAL)
            viewModel.nextStep() // -> VALIDATION
            viewModel.nextStep() // -> CONFIRMATION
            viewModel.nextStep() // -> RESTORING (triggers restore)
            advanceUntilIdle()

            verifySuspend {
                api.restore(
                    RestoreRequest(
                        backupId = "backup-1",
                        mode = "merge",
                        mergeStrategy = "keep_local",
                        dryRun = false,
                        confirmFullWipe = false,
                    ),
                )
            }
        }

    @Test
    fun `KEEP_BACKUP strategy uses keep_backup API value`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.validateBackup(any()) } returns
                AppResult.Success(createValidationResponse())
            everySuspend { api.restore(any()) } returns AppResult.Success(createRestoreResponse())

            val viewModel = RestoreBackupViewModel("backup-1", api, createMockSyncRepository(), errorBus = ErrorBus())
            advanceUntilIdle()

            viewModel.selectMode(RestoreMode.MERGE)
            viewModel.nextStep() // -> MERGE_STRATEGY
            viewModel.selectMergeStrategy(MergeStrategy.KEEP_BACKUP)
            viewModel.nextStep() // -> VALIDATION
            viewModel.nextStep() // -> CONFIRMATION
            viewModel.nextStep() // -> RESTORING (triggers restore)
            advanceUntilIdle()

            verifySuspend {
                api.restore(
                    RestoreRequest(
                        backupId = "backup-1",
                        mode = "merge",
                        mergeStrategy = "keep_backup",
                        dryRun = false,
                        confirmFullWipe = false,
                    ),
                )
            }
        }

    // ========== Error Handling ==========

    @Test
    fun `clearError clears transient error on Ready`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.validateBackup(any()) } returns
                AppResult.Success(createValidationResponse())
            // Use dry run failure to produce a transient error on Ready.
            everySuspend { api.restore(any()) } returns
                AppResult.Failure(TransportError.NetworkUnavailable())

            val viewModel = RestoreBackupViewModel("backup-1", api, createMockSyncRepository(), errorBus = ErrorBus())
            advanceUntilIdle()

            viewModel.selectMergeStrategy(MergeStrategy.KEEP_LOCAL)
            viewModel.performDryRun()
            advanceUntilIdle()
            assertNotNull(assertIs<RestoreBackupUiState.Ready>(viewModel.state.value).error)

            viewModel.clearError()
            assertNull(assertIs<RestoreBackupUiState.Ready>(viewModel.state.value).error)
        }

    // ========== Edge Cases ==========

    @Test
    fun `handles empty import counts`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.validateBackup(any()) } returns
                AppResult.Success(createValidationResponse())
            everySuspend { api.restore(any()) } returns
                AppResult.Success(
                    createRestoreResponse(
                        imported = emptyMap(),
                        skipped = emptyMap(),
                        errors = emptyList(),
                    ),
                )

            val viewModel = RestoreBackupViewModel("backup-1", api, createMockSyncRepository(), errorBus = ErrorBus())
            advanceUntilIdle()

            viewModel.selectMode(RestoreMode.FRESH)
            viewModel.nextStep()
            viewModel.nextStep()
            viewModel.nextStep()
            advanceUntilIdle()

            val ready = assertIs<RestoreBackupUiState.Ready>(viewModel.state.value)
            val results = assertNotNull(ready.restoreResults)
            assertTrue(results.imported.isEmpty())
            assertTrue(results.errors.isEmpty())
        }

    @Test
    fun `handles invalid backup validation`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.validateBackup(any()) } returns
                AppResult.Success(
                    createValidationResponse(
                        valid = false,
                        errors = listOf("Corrupted archive", "Missing manifest"),
                    ),
                )

            val viewModel = RestoreBackupViewModel("backup-1", api, createMockSyncRepository(), errorBus = ErrorBus())
            advanceUntilIdle()

            val ready = assertIs<RestoreBackupUiState.Ready>(viewModel.state.value)
            val validation = assertNotNull(ready.validation)
            assertFalse(validation.valid)
            assertEquals(2, validation.errors.size)
        }

    @Test
    fun `restore preserves mode selection when navigating back and forward`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.validateBackup(any()) } returns
                AppResult.Success(createValidationResponse())

            val viewModel = RestoreBackupViewModel("backup-1", api, createMockSyncRepository(), errorBus = ErrorBus())
            advanceUntilIdle()

            viewModel.selectMode(RestoreMode.MERGE)
            viewModel.nextStep() // -> MERGE_STRATEGY
            viewModel.selectMergeStrategy(MergeStrategy.NEWEST)
            viewModel.nextStep() // -> VALIDATION
            viewModel.previousStep() // -> MERGE_STRATEGY
            viewModel.previousStep() // -> MODE_SELECTION

            // Mode should still be selected
            val ready = assertIs<RestoreBackupUiState.Ready>(viewModel.state.value)
            assertEquals(RestoreMode.MERGE, ready.mode)
            assertEquals(MergeStrategy.NEWEST, ready.mergeStrategy)
        }

    @Test
    fun `full wizard flow for MERGE with KEEP_LOCAL strategy`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.validateBackup(any()) } returns
                AppResult.Success(
                    createValidationResponse(
                        valid = true,
                        expectedCounts = mapOf("books" to 500, "users" to 10),
                    ),
                )
            everySuspend { api.restore(any()) } returns
                AppResult.Success(
                    createRestoreResponse(
                        imported = mapOf("books" to 450, "users" to 8),
                        skipped = mapOf("books" to 50, "users" to 2),
                        duration = "15.3s",
                    ),
                )

            val viewModel = RestoreBackupViewModel("backup-1", api, createMockSyncRepository(), errorBus = ErrorBus())
            advanceUntilIdle()

            // Step 1: Mode selection
            assertEquals(
                RestoreStep.MODE_SELECTION,
                assertIs<RestoreBackupUiState.Ready>(viewModel.state.value).step,
            )
            viewModel.selectMode(RestoreMode.MERGE)
            viewModel.nextStep()

            // Step 2: Merge strategy
            assertEquals(
                RestoreStep.MERGE_STRATEGY,
                assertIs<RestoreBackupUiState.Ready>(viewModel.state.value).step,
            )
            viewModel.selectMergeStrategy(MergeStrategy.KEEP_LOCAL)
            viewModel.nextStep()

            // Step 3: Validation review
            val atValidation = assertIs<RestoreBackupUiState.Ready>(viewModel.state.value)
            assertEquals(RestoreStep.VALIDATION, atValidation.step)
            assertEquals(500, atValidation.validation?.entityCounts?.get("books"))
            viewModel.nextStep()

            // Step 4: Confirmation
            assertEquals(
                RestoreStep.CONFIRMATION,
                assertIs<RestoreBackupUiState.Ready>(viewModel.state.value).step,
            )
            viewModel.nextStep()

            // Step 5 & 6: Restoring -> Results (advance to completion)
            advanceUntilIdle()

            // After completion, should be at Results
            val finalReady = assertIs<RestoreBackupUiState.Ready>(viewModel.state.value)
            assertEquals(RestoreStep.RESULTS, finalReady.step)
            assertFalse(finalReady.isRestoring)
            val results = assertNotNull(finalReady.restoreResults)
            assertEquals(450, results.imported["books"])
            assertEquals(50, results.skipped["books"])
            assertEquals("15.3s", results.duration)
        }

    @Test
    fun `full wizard flow for FRESH restore`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.validateBackup(any()) } returns
                AppResult.Success(createValidationResponse())
            everySuspend { api.restore(any()) } returns AppResult.Success(createRestoreResponse())

            val viewModel = RestoreBackupViewModel("backup-1", api, createMockSyncRepository(), errorBus = ErrorBus())
            advanceUntilIdle()

            // MODE_SELECTION -> VALIDATION (skips MERGE_STRATEGY for FRESH)
            viewModel.selectMode(RestoreMode.FRESH)
            viewModel.nextStep()
            assertEquals(
                RestoreStep.VALIDATION,
                assertIs<RestoreBackupUiState.Ready>(viewModel.state.value).step,
            )

            // VALIDATION -> CONFIRMATION
            viewModel.nextStep()
            assertEquals(
                RestoreStep.CONFIRMATION,
                assertIs<RestoreBackupUiState.Ready>(viewModel.state.value).step,
            )

            // CONFIRMATION -> RESTORING -> RESULTS
            viewModel.nextStep()
            advanceUntilIdle()
            assertEquals(
                RestoreStep.RESULTS,
                assertIs<RestoreBackupUiState.Ready>(viewModel.state.value).step,
            )
        }

    // ========== Post-Restore Sync Tests ==========

    @Test
    fun `FRESH restore triggers forceFullResync after completion`() =
        runTest {
            val api: BackupApiContract = mock()
            val syncRepo: SyncRepository = mock()
            everySuspend { api.validateBackup(any()) } returns
                AppResult.Success(createValidationResponse())
            everySuspend { api.restore(any()) } returns AppResult.Success(createRestoreResponse())
            everySuspend { syncRepo.forceFullResync() } returns AppResult.Success(Unit)
            everySuspend { syncRepo.refreshListeningHistory() } returns AppResult.Success(Unit)

            val viewModel = RestoreBackupViewModel("backup-1", api, syncRepo, errorBus = ErrorBus())
            advanceUntilIdle()

            // Navigate to restore
            viewModel.selectMode(RestoreMode.FRESH)
            viewModel.nextStep() // -> VALIDATION
            viewModel.nextStep() // -> CONFIRMATION
            viewModel.nextStep() // -> RESTORING
            advanceUntilIdle()

            // Verify forceFullResync was called (not refreshListeningHistory)
            verifySuspend { syncRepo.forceFullResync() }
        }

    @Test
    fun `MERGE restore triggers refreshListeningHistory after completion`() =
        runTest {
            val api: BackupApiContract = mock()
            val syncRepo: SyncRepository = mock()
            everySuspend { api.validateBackup(any()) } returns
                AppResult.Success(createValidationResponse())
            everySuspend { api.restore(any()) } returns AppResult.Success(createRestoreResponse())
            everySuspend { syncRepo.forceFullResync() } returns AppResult.Success(Unit)
            everySuspend { syncRepo.refreshListeningHistory() } returns AppResult.Success(Unit)

            val viewModel = RestoreBackupViewModel("backup-1", api, syncRepo, errorBus = ErrorBus())
            advanceUntilIdle()

            // Navigate to restore with MERGE mode
            viewModel.selectMode(RestoreMode.MERGE)
            viewModel.nextStep() // -> MERGE_STRATEGY
            viewModel.selectMergeStrategy(MergeStrategy.KEEP_LOCAL)
            viewModel.nextStep() // -> VALIDATION
            viewModel.nextStep() // -> CONFIRMATION
            viewModel.nextStep() // -> RESTORING
            advanceUntilIdle()

            // Verify refreshListeningHistory was called (not forceFullResync)
            verifySuspend { syncRepo.refreshListeningHistory() }
        }

    // ========== Additional Edge Cases ==========

    @Test
    fun `selectMergeStrategy clears any previous transient error`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.validateBackup(any()) } returns
                AppResult.Success(createValidationResponse())
            // Use dry run failure to produce a transient error on Ready.
            everySuspend { api.restore(any()) } returns
                AppResult.Failure(TransportError.NetworkUnavailable())

            val viewModel = RestoreBackupViewModel("backup-1", api, createMockSyncRepository(), errorBus = ErrorBus())
            advanceUntilIdle()

            viewModel.selectMergeStrategy(MergeStrategy.KEEP_BACKUP)
            viewModel.performDryRun()
            advanceUntilIdle()
            assertNotNull(assertIs<RestoreBackupUiState.Ready>(viewModel.state.value).error)

            viewModel.selectMergeStrategy(MergeStrategy.KEEP_LOCAL)
            assertNull(assertIs<RestoreBackupUiState.Ready>(viewModel.state.value).error)
        }

    @Test
    fun `validation handles warnings in addition to errors`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.validateBackup(any()) } returns
                AppResult.Success(
                    createValidationResponse(
                        valid = true,
                        warnings = listOf("Some entities may have outdated references", "Media files not verified"),
                    ),
                )

            val viewModel = RestoreBackupViewModel("backup-1", api, createMockSyncRepository(), errorBus = ErrorBus())
            advanceUntilIdle()

            val ready = assertIs<RestoreBackupUiState.Ready>(viewModel.state.value)
            val validation = assertNotNull(ready.validation)
            assertTrue(validation.valid)
            assertEquals(2, validation.warnings.size)
            assertTrue(validation.warnings.contains("Some entities may have outdated references"))
        }

    @Test
    fun `dry run handles errors in response without throwing`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.validateBackup(any()) } returns
                AppResult.Success(createValidationResponse())
            everySuspend { api.restore(any()) } returns
                AppResult.Success(
                    createRestoreResponse(
                        imported = mapOf("books" to 95),
                        skipped = mapOf("books" to 3),
                        errors =
                            listOf(
                                RestoreError(entityType = "book", entityId = "book-corrupt", error = "Malformed JSON"),
                                RestoreError(entityType = "user", entityId = "user-invalid", error = "Missing email"),
                            ),
                        duration = "1.2s",
                    ),
                )

            val viewModel = RestoreBackupViewModel("backup-1", api, createMockSyncRepository(), errorBus = ErrorBus())
            advanceUntilIdle()

            viewModel.selectMode(RestoreMode.MERGE)
            viewModel.selectMergeStrategy(MergeStrategy.KEEP_LOCAL)
            viewModel.performDryRun()
            advanceUntilIdle()

            // Should succeed (not throw) even with errors in response
            val ready = assertIs<RestoreBackupUiState.Ready>(viewModel.state.value)
            assertNull(ready.error)
            val dryRun = assertNotNull(ready.dryRunResults)
            assertEquals(2, dryRun.errors.size)
            assertEquals("book-corrupt", dryRun.errors[0].entityId)
        }

    @Test
    fun `validation handles null expectedCounts from server`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.validateBackup(any()) } returns
                AppResult.Success(
                    ValidationResponse(
                        valid = true,
                        version = "1.0",
                        serverName = "Test",
                        expectedCounts = null, // Server returns null
                        errors = emptyList(),
                        warnings = emptyList(),
                    ),
                )

            val viewModel = RestoreBackupViewModel("backup-1", api, createMockSyncRepository(), errorBus = ErrorBus())
            advanceUntilIdle()

            val ready = assertIs<RestoreBackupUiState.Ready>(viewModel.state.value)
            val validation = assertNotNull(ready.validation)
            assertTrue(validation.entityCounts.isEmpty())
        }

    @Test
    fun `validation handles null version and serverName`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.validateBackup(any()) } returns
                AppResult.Success(
                    ValidationResponse(
                        valid = true,
                        version = null,
                        serverName = null,
                        expectedCounts = mapOf("books" to 10),
                        errors = emptyList(),
                        warnings = emptyList(),
                    ),
                )

            val viewModel = RestoreBackupViewModel("backup-1", api, createMockSyncRepository(), errorBus = ErrorBus())
            advanceUntilIdle()

            val ready = assertIs<RestoreBackupUiState.Ready>(viewModel.state.value)
            val validation = assertNotNull(ready.validation)
            assertNull(validation.version)
            assertNull(validation.serverName)
        }

    @Test
    fun `nextStep from RESULTS stays at RESULTS`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.validateBackup(any()) } returns
                AppResult.Success(createValidationResponse())
            everySuspend { api.restore(any()) } returns AppResult.Success(createRestoreResponse())

            val viewModel = RestoreBackupViewModel("backup-1", api, createMockSyncRepository(), errorBus = ErrorBus())
            advanceUntilIdle()

            viewModel.selectMode(RestoreMode.FRESH)
            viewModel.nextStep() // -> VALIDATION
            viewModel.nextStep() // -> CONFIRMATION
            viewModel.nextStep() // -> RESTORING
            advanceUntilIdle() // -> RESULTS

            assertEquals(
                RestoreStep.RESULTS,
                assertIs<RestoreBackupUiState.Ready>(viewModel.state.value).step,
            )

            // Try to go forward again - should stay at RESULTS
            viewModel.nextStep()
            assertEquals(
                RestoreStep.RESULTS,
                assertIs<RestoreBackupUiState.Ready>(viewModel.state.value).step,
            )
        }

    @Test
    fun `switching from MERGE to FRESH mode preserves merge strategy`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.validateBackup(any()) } returns
                AppResult.Success(createValidationResponse())

            val viewModel = RestoreBackupViewModel("backup-1", api, createMockSyncRepository(), errorBus = ErrorBus())
            advanceUntilIdle()

            // Select MERGE and set strategy
            viewModel.selectMode(RestoreMode.MERGE)
            viewModel.selectMergeStrategy(MergeStrategy.NEWEST)
            assertEquals(
                MergeStrategy.NEWEST,
                assertIs<RestoreBackupUiState.Ready>(viewModel.state.value).mergeStrategy,
            )

            // Switch to FRESH
            viewModel.selectMode(RestoreMode.FRESH)

            // Strategy is preserved (UI might still need it if user switches back)
            assertEquals(
                MergeStrategy.NEWEST,
                assertIs<RestoreBackupUiState.Ready>(viewModel.state.value).mergeStrategy,
            )
        }

    @Test
    fun `multiple dry runs update results`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.validateBackup(any()) } returns
                AppResult.Success(createValidationResponse())
            // First dry run
            everySuspend { api.restore(any()) } returns
                AppResult.Success(
                    createRestoreResponse(
                        imported = mapOf("books" to 50),
                        duration = "1.0s",
                    ),
                )

            val viewModel = RestoreBackupViewModel("backup-1", api, createMockSyncRepository(), errorBus = ErrorBus())
            advanceUntilIdle()

            viewModel.selectMode(RestoreMode.MERGE)
            viewModel.selectMergeStrategy(MergeStrategy.KEEP_LOCAL)
            viewModel.performDryRun()
            advanceUntilIdle()

            assertEquals(
                50,
                assertIs<RestoreBackupUiState.Ready>(viewModel.state.value)
                    .dryRunResults
                    ?.willImport
                    ?.get("books"),
            )

            // Change strategy and run second dry run with different results
            viewModel.selectMergeStrategy(MergeStrategy.KEEP_BACKUP)
            everySuspend { api.restore(any()) } returns
                AppResult.Success(
                    createRestoreResponse(
                        imported = mapOf("books" to 100),
                        duration = "1.5s",
                    ),
                )
            viewModel.performDryRun()
            advanceUntilIdle()

            // Results should be updated
            assertEquals(
                100,
                assertIs<RestoreBackupUiState.Ready>(viewModel.state.value)
                    .dryRunResults
                    ?.willImport
                    ?.get("books"),
            )
        }

    @Test
    fun `FRESH restore succeeds even when sync fails`() =
        runTest {
            val api: BackupApiContract = mock()
            val syncRepo: SyncRepository = mock()
            everySuspend { api.validateBackup(any()) } returns
                AppResult.Success(createValidationResponse())
            everySuspend { api.restore(any()) } returns
                AppResult.Success(
                    createRestoreResponse(
                        imported = mapOf("books" to 100),
                    ),
                )
            // Sync fails after restore
            everySuspend { syncRepo.forceFullResync() } returns
                AppResult.Failure(TransportError.NetworkUnavailable())

            val viewModel = RestoreBackupViewModel("backup-1", api, syncRepo, errorBus = ErrorBus())
            advanceUntilIdle()

            viewModel.selectMode(RestoreMode.FRESH)
            viewModel.nextStep() // -> VALIDATION
            viewModel.nextStep() // -> CONFIRMATION
            viewModel.nextStep() // -> RESTORING
            advanceUntilIdle()

            // Restore should still be considered successful - data is on server
            val ready = assertIs<RestoreBackupUiState.Ready>(viewModel.state.value)
            assertEquals(RestoreStep.RESULTS, ready.step)
            val results = assertNotNull(ready.restoreResults)
            assertEquals(100, results.imported["books"])
            // Note: Currently sync failure is silently ignored - this test documents current behavior
            // TODO: Consider if we should warn user that local sync failed
        }

    @Test
    fun `MERGE restore succeeds even when sync fails`() =
        runTest {
            val api: BackupApiContract = mock()
            val syncRepo: SyncRepository = mock()
            everySuspend { api.validateBackup(any()) } returns
                AppResult.Success(createValidationResponse())
            everySuspend { api.restore(any()) } returns AppResult.Success(createRestoreResponse())
            // Sync fails after restore
            everySuspend { syncRepo.refreshListeningHistory() } returns
                AppResult.Failure(TransportError.NetworkUnavailable())

            val viewModel = RestoreBackupViewModel("backup-1", api, syncRepo, errorBus = ErrorBus())
            advanceUntilIdle()

            viewModel.selectMode(RestoreMode.MERGE)
            viewModel.nextStep() // -> MERGE_STRATEGY
            viewModel.selectMergeStrategy(MergeStrategy.KEEP_LOCAL)
            viewModel.nextStep() // -> VALIDATION
            viewModel.nextStep() // -> CONFIRMATION
            viewModel.nextStep() // -> RESTORING
            advanceUntilIdle()

            // Restore completes successfully even with sync failure
            val ready = assertIs<RestoreBackupUiState.Ready>(viewModel.state.value)
            assertEquals(RestoreStep.RESULTS, ready.step)
            assertNotNull(ready.restoreResults)
        }

    @Test
    fun `FRESH mode API value is full`() {
        assertEquals("full", RestoreMode.FRESH.apiValue)
    }

    @Test
    fun `MERGE mode API value is merge`() {
        assertEquals("merge", RestoreMode.MERGE.apiValue)
    }

    @Test
    fun `all merge strategies have correct API values`() {
        assertEquals("keep_local", MergeStrategy.KEEP_LOCAL.apiValue)
        assertEquals("keep_backup", MergeStrategy.KEEP_BACKUP.apiValue)
        assertEquals("newest", MergeStrategy.NEWEST.apiValue)
    }

    @Test
    fun `dry run clears error from previous failed dry run`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.validateBackup(any()) } returns
                AppResult.Success(createValidationResponse())
            // First dry run fails
            everySuspend { api.restore(any()) } returns
                AppResult.Failure(TransportError.NetworkUnavailable())

            val viewModel = RestoreBackupViewModel("backup-1", api, createMockSyncRepository(), errorBus = ErrorBus())
            advanceUntilIdle()

            viewModel.selectMode(RestoreMode.MERGE)
            viewModel.selectMergeStrategy(MergeStrategy.KEEP_LOCAL)
            viewModel.performDryRun()
            advanceUntilIdle()

            assertNotNull(assertIs<RestoreBackupUiState.Ready>(viewModel.state.value).error)

            // Second dry run succeeds
            everySuspend { api.restore(any()) } returns AppResult.Success(createRestoreResponse())
            viewModel.performDryRun()
            advanceUntilIdle()

            // Error should be cleared
            val ready = assertIs<RestoreBackupUiState.Ready>(viewModel.state.value)
            assertNull(ready.error)
            assertNotNull(ready.dryRunResults)
        }

    @Test
    fun `validation with empty entity counts`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.validateBackup(any()) } returns
                AppResult.Success(
                    createValidationResponse(
                        expectedCounts = emptyMap(),
                    ),
                )

            val viewModel = RestoreBackupViewModel("backup-1", api, createMockSyncRepository(), errorBus = ErrorBus())
            advanceUntilIdle()

            val ready = assertIs<RestoreBackupUiState.Ready>(viewModel.state.value)
            val validation = assertNotNull(ready.validation)
            assertTrue(validation.entityCounts.isEmpty())
        }

    @Test
    fun `isValidating is false after validation completes`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.validateBackup(any()) } returns
                AppResult.Success(createValidationResponse())

            val viewModel = RestoreBackupViewModel("backup-1", api, createMockSyncRepository(), errorBus = ErrorBus())
            advanceUntilIdle()

            // After validation completes, isValidating should be false
            val ready = assertIs<RestoreBackupUiState.Ready>(viewModel.state.value)
            assertFalse(ready.isValidating)
            assertNotNull(ready.validation)
        }

    @Test
    fun `isValidating is false after dry run completes`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.validateBackup(any()) } returns
                AppResult.Success(createValidationResponse())
            everySuspend { api.restore(any()) } returns AppResult.Success(createRestoreResponse())

            val viewModel = RestoreBackupViewModel("backup-1", api, createMockSyncRepository(), errorBus = ErrorBus())
            advanceUntilIdle()

            viewModel.selectMode(RestoreMode.MERGE)
            viewModel.selectMergeStrategy(MergeStrategy.KEEP_LOCAL)
            viewModel.performDryRun()
            advanceUntilIdle()

            // After dry run completes, isValidating should be false
            val ready = assertIs<RestoreBackupUiState.Ready>(viewModel.state.value)
            assertFalse(ready.isValidating)
            assertNotNull(ready.dryRunResults)
        }

    @Test
    fun `isRestoring is false after restore completes`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.validateBackup(any()) } returns
                AppResult.Success(createValidationResponse())
            everySuspend { api.restore(any()) } returns AppResult.Success(createRestoreResponse())

            val viewModel = RestoreBackupViewModel("backup-1", api, createMockSyncRepository(), errorBus = ErrorBus())
            advanceUntilIdle()

            viewModel.selectMode(RestoreMode.FRESH)
            viewModel.nextStep() // -> VALIDATION
            viewModel.nextStep() // -> CONFIRMATION
            viewModel.nextStep() // -> RESTORING (triggers restore)
            advanceUntilIdle()

            // After restore completes, isRestoring should be false
            val ready = assertIs<RestoreBackupUiState.Ready>(viewModel.state.value)
            assertFalse(ready.isRestoring)
            assertEquals(RestoreStep.RESULTS, ready.step)
        }

    @Test
    fun `restore failure does not advance to RESULTS step`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.validateBackup(any()) } returns
                AppResult.Success(createValidationResponse())
            everySuspend { api.restore(any()) } returns
                AppResult.Failure(TransportError.Server4xx(statusCode = 503))

            val viewModel = RestoreBackupViewModel("backup-1", api, createMockSyncRepository(), errorBus = ErrorBus())
            advanceUntilIdle()

            viewModel.selectMode(RestoreMode.FRESH)
            viewModel.nextStep() // -> VALIDATION
            viewModel.nextStep() // -> CONFIRMATION
            viewModel.nextStep() // -> RESTORING
            advanceUntilIdle()

            // Should stay at RESTORING, not advance to RESULTS
            val ready = assertIs<RestoreBackupUiState.Ready>(viewModel.state.value)
            assertEquals(RestoreStep.RESTORING, ready.step)
            assertNotNull(ready.error)
            assertNull(ready.restoreResults)
        }

    @Test
    fun `dry run uses default MERGE mode when mode is null`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.validateBackup(any()) } returns
                AppResult.Success(createValidationResponse())
            everySuspend { api.restore(any()) } returns AppResult.Success(createRestoreResponse())

            val viewModel = RestoreBackupViewModel("backup-1", api, createMockSyncRepository(), errorBus = ErrorBus())
            advanceUntilIdle()

            // Don't select mode - leave it null
            assertNull(assertIs<RestoreBackupUiState.Ready>(viewModel.state.value).mode)

            viewModel.performDryRun()
            advanceUntilIdle()

            // Should use MERGE as default
            verifySuspend {
                api.restore(
                    RestoreRequest(
                        backupId = "backup-1",
                        mode = "merge", // Default when mode is null
                        mergeStrategy = null,
                        dryRun = true,
                        confirmFullWipe = false,
                    ),
                )
            }
        }
}
