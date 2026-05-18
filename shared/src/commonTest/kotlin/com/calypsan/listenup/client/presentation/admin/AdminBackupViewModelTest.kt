package com.calypsan.listenup.client.presentation.admin

import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.data.remote.BackupApiContract
import com.calypsan.listenup.client.data.remote.model.BackupResponse
import com.calypsan.listenup.client.data.remote.model.ValidationResponse
import dev.mokkery.answering.returns
import dev.mokkery.answering.sequentiallyReturns
import dev.mokkery.everySuspend
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
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
import com.calypsan.listenup.client.core.error.ErrorBus

/**
 * Tests for AdminBackupViewModel.
 *
 * Covers:
 * - Loading backup list
 * - Creating backups with different options
 * - Deleting backups with confirmation
 * - Validating backups
 * - Error handling for all operations
 * - State transitions
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdminBackupViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private fun createBackupResponse(
        id: String = "backup-1",
        path: String = "/backups/backup-1.listenup.zip",
        size: Long = 1024 * 1024, // 1 MB
        createdAt: String = "2024-01-15T10:30:00Z",
        checksum: String? = "abc123",
    ) = BackupResponse(
        id = id,
        path = path,
        size = size,
        createdAt = createdAt,
        checksum = checksum,
    )

    private fun createValidationResponse(
        valid: Boolean = true,
        version: String = "1.0",
        serverName: String = "Test Server",
        expectedCounts: Map<String, Int> =
            mapOf(
                "users" to 5,
                "books" to 100,
                "contributors" to 50,
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
            everySuspend { api.listBackups() } returns AppResult.Success(emptyList())

            val viewModel = AdminBackupViewModel(api, errorBus = ErrorBus())

            assertIs<AdminBackupUiState.Loading>(viewModel.state.value)
        }

    // ========== Load Backups ==========

    @Test
    fun `loadBackups transitions to Ready with backups sorted by date`() =
        runTest {
            val api: BackupApiContract = mock()
            val backups =
                listOf(
                    createBackupResponse(id = "older", createdAt = "2024-01-01T00:00:00Z"),
                    createBackupResponse(id = "newer", createdAt = "2024-01-15T00:00:00Z"),
                    createBackupResponse(id = "middle", createdAt = "2024-01-10T00:00:00Z"),
                )
            everySuspend { api.listBackups() } returns AppResult.Success(backups)

            val viewModel = AdminBackupViewModel(api, errorBus = ErrorBus())
            advanceUntilIdle()

            val ready = assertIs<AdminBackupUiState.Ready>(viewModel.state.value)
            assertEquals(3, ready.backups.size)
            // Should be sorted newest first
            assertEquals("newer", ready.backups[0].id)
            assertEquals("middle", ready.backups[1].id)
            assertEquals("older", ready.backups[2].id)
        }

    @Test
    fun `loadBackups handles empty list`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.listBackups() } returns AppResult.Success(emptyList())

            val viewModel = AdminBackupViewModel(api, errorBus = ErrorBus())
            advanceUntilIdle()

            val ready = assertIs<AdminBackupUiState.Ready>(viewModel.state.value)
            assertTrue(ready.backups.isEmpty())
            assertNull(ready.error)
        }

    @Test
    fun `loadBackups initial failure transitions to Error`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.listBackups() } returns
                AppResult.Failure(TransportError.NetworkUnavailable())

            val viewModel = AdminBackupViewModel(api, errorBus = ErrorBus())
            advanceUntilIdle()

            assertIs<AdminBackupUiState.Error>(viewModel.state.value)
        }

    @Test
    fun `loadBackups handles invalid date format gracefully`() =
        runTest {
            val api: BackupApiContract = mock()
            val backups =
                listOf(
                    createBackupResponse(id = "valid", createdAt = "2024-01-15T00:00:00Z"),
                    createBackupResponse(id = "invalid", createdAt = "not-a-date"),
                )
            everySuspend { api.listBackups() } returns AppResult.Success(backups)

            val viewModel = AdminBackupViewModel(api, errorBus = ErrorBus())
            advanceUntilIdle()

            // Should not crash, invalid date should use epoch (Timestamp(0L))
            val ready = assertIs<AdminBackupUiState.Ready>(viewModel.state.value)
            assertEquals(2, ready.backups.size)
            assertEquals("valid", ready.backups[0].id)
        }

    // ========== Create Backup ==========

    @Test
    fun `createBackup with default options`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.listBackups() } returns AppResult.Success(emptyList())
            everySuspend { api.createBackup(includeImages = false, includeEvents = true) } returns
                AppResult.Success(createBackupResponse(id = "new-backup"))

            val viewModel = AdminBackupViewModel(api, errorBus = ErrorBus())
            advanceUntilIdle()

            viewModel.createBackup(includeImages = false, includeEvents = true)
            advanceUntilIdle()

            // After completion, isCreating should be false
            val ready = assertIs<AdminBackupUiState.Ready>(viewModel.state.value)
            assertFalse(ready.isCreating)
            verifySuspend { api.createBackup(includeImages = false, includeEvents = true) }
        }

    @Test
    fun `createBackup with images included`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.listBackups() } returns AppResult.Success(emptyList())
            everySuspend { api.createBackup(includeImages = true, includeEvents = true) } returns
                AppResult.Success(createBackupResponse())

            val viewModel = AdminBackupViewModel(api, errorBus = ErrorBus())
            advanceUntilIdle()

            viewModel.createBackup(includeImages = true, includeEvents = true)
            advanceUntilIdle()

            verifySuspend { api.createBackup(includeImages = true, includeEvents = true) }
        }

    @Test
    fun `createBackup without events`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.listBackups() } returns AppResult.Success(emptyList())
            everySuspend { api.createBackup(includeImages = false, includeEvents = false) } returns
                AppResult.Success(createBackupResponse())

            val viewModel = AdminBackupViewModel(api, errorBus = ErrorBus())
            advanceUntilIdle()

            viewModel.createBackup(includeImages = false, includeEvents = false)
            advanceUntilIdle()

            verifySuspend { api.createBackup(includeImages = false, includeEvents = false) }
        }

    @Test
    fun `createBackup reloads list on success`() =
        runTest {
            val api: BackupApiContract = mock()
            // Use sequentiallyReturns for multiple calls
            everySuspend { api.listBackups() } sequentiallyReturns
                listOf(
                    AppResult.Success(listOf(createBackupResponse(id = "initial-backup"))),
                    AppResult.Success(listOf(createBackupResponse(id = "reloaded-backup"))),
                )
            everySuspend { api.createBackup(includeImages = false, includeEvents = true) } returns
                AppResult.Success(createBackupResponse(id = "new-backup"))

            val viewModel = AdminBackupViewModel(api, errorBus = ErrorBus())
            advanceUntilIdle()

            val initialReady = assertIs<AdminBackupUiState.Ready>(viewModel.state.value)
            assertEquals("initial-backup", initialReady.backups[0].id)

            viewModel.createBackup(includeImages = false, includeEvents = true)
            advanceUntilIdle()

            // List should have been reloaded (now shows reloaded-backup)
            val reloadedReady = assertIs<AdminBackupUiState.Ready>(viewModel.state.value)
            assertEquals("reloaded-backup", reloadedReady.backups[0].id)
            // Verify listBackups was called twice
            verifySuspend(VerifyMode.exactly(2)) { api.listBackups() }
        }

    @Test
    fun `createBackup handles error`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.listBackups() } returns AppResult.Success(emptyList())
            everySuspend { api.createBackup(includeImages = false, includeEvents = true) } returns
                AppResult.Failure(TransportError.Server4xx(statusCode = 507))

            val viewModel = AdminBackupViewModel(api, errorBus = ErrorBus())
            advanceUntilIdle()

            viewModel.createBackup(includeImages = false, includeEvents = true)
            advanceUntilIdle()

            val ready = assertIs<AdminBackupUiState.Ready>(viewModel.state.value)
            assertFalse(ready.isCreating)
            assertNotNull(ready.error)
        }

    // ========== Delete Backup ==========

    @Test
    fun `showDeleteConfirmation sets deleteConfirmBackup`() =
        runTest {
            val api: BackupApiContract = mock()
            val backup = createBackupResponse(id = "to-delete")
            everySuspend { api.listBackups() } returns AppResult.Success(listOf(backup))

            val viewModel = AdminBackupViewModel(api, errorBus = ErrorBus())
            advanceUntilIdle()

            val initialReady = assertIs<AdminBackupUiState.Ready>(viewModel.state.value)
            val backupInfo = initialReady.backups[0]
            viewModel.showDeleteConfirmation(backupInfo)

            val ready = assertIs<AdminBackupUiState.Ready>(viewModel.state.value)
            assertEquals(backupInfo.id, ready.deleteConfirmBackup?.id)
        }

    @Test
    fun `dismissDeleteConfirmation clears deleteConfirmBackup`() =
        runTest {
            val api: BackupApiContract = mock()
            val backup = createBackupResponse()
            everySuspend { api.listBackups() } returns AppResult.Success(listOf(backup))

            val viewModel = AdminBackupViewModel(api, errorBus = ErrorBus())
            advanceUntilIdle()

            val initialReady = assertIs<AdminBackupUiState.Ready>(viewModel.state.value)
            viewModel.showDeleteConfirmation(initialReady.backups[0])
            val afterShow = assertIs<AdminBackupUiState.Ready>(viewModel.state.value)
            assertNotNull(afterShow.deleteConfirmBackup)

            viewModel.dismissDeleteConfirmation()
            val afterDismiss = assertIs<AdminBackupUiState.Ready>(viewModel.state.value)
            assertNull(afterDismiss.deleteConfirmBackup)
        }

    @Test
    fun `deleteBackup removes backup from list`() =
        runTest {
            val api: BackupApiContract = mock()
            val backups =
                listOf(
                    createBackupResponse(id = "backup-1"),
                    createBackupResponse(id = "backup-2"),
                )
            everySuspend { api.listBackups() } returns AppResult.Success(backups)
            everySuspend { api.deleteBackup("backup-1") } returns AppResult.Success(Unit)

            val viewModel = AdminBackupViewModel(api, errorBus = ErrorBus())
            advanceUntilIdle()
            val initialReady = assertIs<AdminBackupUiState.Ready>(viewModel.state.value)
            assertEquals(2, initialReady.backups.size)

            val toDelete = initialReady.backups.first { it.id == "backup-1" }
            viewModel.deleteBackup(toDelete)
            advanceUntilIdle()

            val ready = assertIs<AdminBackupUiState.Ready>(viewModel.state.value)
            assertEquals(1, ready.backups.size)
            assertEquals("backup-2", ready.backups[0].id)
            assertFalse(ready.isDeleting)
        }

    @Test
    fun `deleteBackup clears confirmation dialog`() =
        runTest {
            val api: BackupApiContract = mock()
            val backup = createBackupResponse()
            everySuspend { api.listBackups() } returns AppResult.Success(listOf(backup))
            everySuspend { api.deleteBackup(backup.id) } returns AppResult.Success(Unit)

            val viewModel = AdminBackupViewModel(api, errorBus = ErrorBus())
            advanceUntilIdle()

            val initialReady = assertIs<AdminBackupUiState.Ready>(viewModel.state.value)
            val backupInfo = initialReady.backups[0]
            viewModel.showDeleteConfirmation(backupInfo)
            val afterShow = assertIs<AdminBackupUiState.Ready>(viewModel.state.value)
            assertNotNull(afterShow.deleteConfirmBackup)

            viewModel.deleteBackup(backupInfo)
            advanceUntilIdle()

            val ready = assertIs<AdminBackupUiState.Ready>(viewModel.state.value)
            assertNull(ready.deleteConfirmBackup)
        }

    @Test
    fun `deleteBackup handles error`() =
        runTest {
            val api: BackupApiContract = mock()
            val backup = createBackupResponse()
            everySuspend { api.listBackups() } returns AppResult.Success(listOf(backup))
            everySuspend { api.deleteBackup(backup.id) } returns
                AppResult.Failure(TransportError.Server4xx(statusCode = 409))

            val viewModel = AdminBackupViewModel(api, errorBus = ErrorBus())
            advanceUntilIdle()

            val initialReady = assertIs<AdminBackupUiState.Ready>(viewModel.state.value)
            viewModel.deleteBackup(initialReady.backups[0])
            advanceUntilIdle()

            val ready = assertIs<AdminBackupUiState.Ready>(viewModel.state.value)
            assertFalse(ready.isDeleting)
            assertNotNull(ready.error)
            // Backup should still be in list
            assertEquals(1, ready.backups.size)
        }

    // ========== Validate Backup ==========

    @Test
    fun `validateBackup clears validatingBackupId after completion`() =
        runTest {
            val api: BackupApiContract = mock()
            val backup = createBackupResponse(id = "backup-1")
            everySuspend { api.listBackups() } returns AppResult.Success(listOf(backup))
            everySuspend { api.validateBackup("backup-1") } returns
                AppResult.Success(createValidationResponse())

            val viewModel = AdminBackupViewModel(api, errorBus = ErrorBus())
            advanceUntilIdle()

            val initialReady = assertIs<AdminBackupUiState.Ready>(viewModel.state.value)
            viewModel.validateBackup(initialReady.backups[0])
            advanceUntilIdle()

            // After completion, validatingBackupId should be cleared
            val ready = assertIs<AdminBackupUiState.Ready>(viewModel.state.value)
            assertNull(ready.validatingBackupId)
            // And we should have a validation result
            assertNotNull(ready.validationResult)
        }

    @Test
    fun `validateBackup stores validation result`() =
        runTest {
            val api: BackupApiContract = mock()
            val backup = createBackupResponse()
            val validation =
                createValidationResponse(
                    valid = true,
                    version = "1.0",
                    serverName = "My Server",
                    expectedCounts = mapOf("books" to 42),
                )
            everySuspend { api.listBackups() } returns AppResult.Success(listOf(backup))
            everySuspend { api.validateBackup(backup.id) } returns AppResult.Success(validation)

            val viewModel = AdminBackupViewModel(api, errorBus = ErrorBus())
            advanceUntilIdle()

            val initialReady = assertIs<AdminBackupUiState.Ready>(viewModel.state.value)
            viewModel.validateBackup(initialReady.backups[0])
            advanceUntilIdle()

            val ready = assertIs<AdminBackupUiState.Ready>(viewModel.state.value)
            val result = ready.validationResult
            assertNotNull(result)
            assertTrue(result.valid)
            assertEquals("1.0", result.version)
            assertEquals("My Server", result.serverName)
            assertEquals(42, result.entityCounts["books"])
        }

    @Test
    fun `validateBackup handles invalid backup`() =
        runTest {
            val api: BackupApiContract = mock()
            val backup = createBackupResponse()
            val validation =
                createValidationResponse(
                    valid = false,
                    errors = listOf("Missing manifest.json", "Unsupported version"),
                )
            everySuspend { api.listBackups() } returns AppResult.Success(listOf(backup))
            everySuspend { api.validateBackup(backup.id) } returns AppResult.Success(validation)

            val viewModel = AdminBackupViewModel(api, errorBus = ErrorBus())
            advanceUntilIdle()

            val initialReady = assertIs<AdminBackupUiState.Ready>(viewModel.state.value)
            viewModel.validateBackup(initialReady.backups[0])
            advanceUntilIdle()

            val ready = assertIs<AdminBackupUiState.Ready>(viewModel.state.value)
            val result = ready.validationResult
            assertNotNull(result)
            assertFalse(result.valid)
            assertEquals(2, result.errors.size)
            assertTrue(result.errors.contains("Missing manifest.json"))
        }

    @Test
    fun `validateBackup handles validation with warnings`() =
        runTest {
            val api: BackupApiContract = mock()
            val backup = createBackupResponse()
            val validation =
                createValidationResponse(
                    valid = true,
                    warnings = listOf("Some images may be missing"),
                )
            everySuspend { api.listBackups() } returns AppResult.Success(listOf(backup))
            everySuspend { api.validateBackup(backup.id) } returns AppResult.Success(validation)

            val viewModel = AdminBackupViewModel(api, errorBus = ErrorBus())
            advanceUntilIdle()

            val initialReady = assertIs<AdminBackupUiState.Ready>(viewModel.state.value)
            viewModel.validateBackup(initialReady.backups[0])
            advanceUntilIdle()

            val ready = assertIs<AdminBackupUiState.Ready>(viewModel.state.value)
            val result = ready.validationResult
            assertNotNull(result)
            assertTrue(result.valid)
            assertEquals(1, result.warnings.size)
        }

    @Test
    fun `validateBackup handles network error`() =
        runTest {
            val api: BackupApiContract = mock()
            val backup = createBackupResponse()
            everySuspend { api.listBackups() } returns AppResult.Success(listOf(backup))
            everySuspend { api.validateBackup(backup.id) } returns
                AppResult.Failure(TransportError.NetworkUnavailable())

            val viewModel = AdminBackupViewModel(api, errorBus = ErrorBus())
            advanceUntilIdle()

            val initialReady = assertIs<AdminBackupUiState.Ready>(viewModel.state.value)
            viewModel.validateBackup(initialReady.backups[0])
            advanceUntilIdle()

            val ready = assertIs<AdminBackupUiState.Ready>(viewModel.state.value)
            assertNull(ready.validatingBackupId)
            assertNull(ready.validationResult)
            assertNotNull(ready.error)
        }

    @Test
    fun `dismissValidation clears validation result`() =
        runTest {
            val api: BackupApiContract = mock()
            val backup = createBackupResponse()
            everySuspend { api.listBackups() } returns AppResult.Success(listOf(backup))
            everySuspend { api.validateBackup(backup.id) } returns
                AppResult.Success(createValidationResponse())

            val viewModel = AdminBackupViewModel(api, errorBus = ErrorBus())
            advanceUntilIdle()

            val initialReady = assertIs<AdminBackupUiState.Ready>(viewModel.state.value)
            viewModel.validateBackup(initialReady.backups[0])
            advanceUntilIdle()
            val afterValidate = assertIs<AdminBackupUiState.Ready>(viewModel.state.value)
            assertNotNull(afterValidate.validationResult)

            viewModel.dismissValidation()
            val ready = assertIs<AdminBackupUiState.Ready>(viewModel.state.value)
            assertNull(ready.validationResult)
        }

    // ========== Error Handling ==========

    @Test
    fun `clearError noops in Error state`() =
        runTest {
            val api: BackupApiContract = mock()
            everySuspend { api.listBackups() } returns
                AppResult.Failure(TransportError.NetworkUnavailable())

            val viewModel = AdminBackupViewModel(api, errorBus = ErrorBus())
            advanceUntilIdle()
            // Initial failure lands in terminal Error; clearError is a no-op there.
            assertIs<AdminBackupUiState.Error>(viewModel.state.value)

            viewModel.clearError()
            assertIs<AdminBackupUiState.Error>(viewModel.state.value)
        }

    // ========== Edge Cases ==========

    @Test
    fun `handles backup with null checksum`() =
        runTest {
            val api: BackupApiContract = mock()
            val backup = createBackupResponse(checksum = null)
            everySuspend { api.listBackups() } returns AppResult.Success(listOf(backup))

            val viewModel = AdminBackupViewModel(api, errorBus = ErrorBus())
            advanceUntilIdle()

            val ready = assertIs<AdminBackupUiState.Ready>(viewModel.state.value)
            assertEquals(1, ready.backups.size)
            assertNull(ready.backups[0].checksum)
        }

    @Test
    fun `handles large backup size formatting`() =
        runTest {
            val api: BackupApiContract = mock()
            val backup = createBackupResponse(size = 2L * 1024 * 1024 * 1024) // 2 GB
            everySuspend { api.listBackups() } returns AppResult.Success(listOf(backup))

            val viewModel = AdminBackupViewModel(api, errorBus = ErrorBus())
            advanceUntilIdle()

            val ready = assertIs<AdminBackupUiState.Ready>(viewModel.state.value)
            val backupInfo = ready.backups[0]
            assertTrue(backupInfo.sizeFormatted.contains("GB"))
        }
}
