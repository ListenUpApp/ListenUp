package com.calypsan.listenup.client.presentation.admin

import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.domain.model.AccessMode
import com.calypsan.listenup.client.domain.model.Library
import com.calypsan.listenup.client.domain.repository.AdminRepository
import dev.mokkery.answering.returns
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.calypsan.listenup.client.core.error.ErrorBus

@OptIn(ExperimentalCoroutinesApi::class)
class LibrarySettingsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private fun createLibrary(
        id: String = "lib-1",
        name: String = "Main Library",
        accessMode: AccessMode = AccessMode.OPEN,
        skipInbox: Boolean = false,
    ) = Library(
        id = id,
        name = name,
        ownerId = "owner-1",
        scanPaths = listOf("/path/to/audiobooks"),
        skipInbox = skipInbox,
        accessMode = accessMode,
        createdAt = "2024-01-01T00:00:00Z",
        updatedAt = "2024-01-01T00:00:00Z",
    )

    private fun networkFailure() = AppResult.Failure(TransportError.NetworkUnavailable())

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is Loading`() =
        runTest {
            val adminRepository: AdminRepository = mock()
            everySuspend { adminRepository.getLibrary("lib-1") } returns AppResult.Success(createLibrary())

            val viewModel =
                LibrarySettingsViewModel(
                    libraryId = "lib-1",
                    adminRepository = adminRepository,
                    errorBus = ErrorBus(),
                )

            assertIs<LibrarySettingsUiState.Loading>(viewModel.state.value)
        }

    @Test
    fun `loadLibrary transitions to Ready with library details`() =
        runTest {
            val adminRepository: AdminRepository = mock()
            val library = createLibrary(accessMode = AccessMode.RESTRICTED, skipInbox = true)
            everySuspend { adminRepository.getLibrary("lib-1") } returns AppResult.Success(library)

            val viewModel =
                LibrarySettingsViewModel(
                    libraryId = "lib-1",
                    adminRepository = adminRepository,
                    errorBus = ErrorBus(),
                )
            advanceUntilIdle()

            val ready = assertIs<LibrarySettingsUiState.Ready>(viewModel.state.value)
            assertEquals(library, ready.library)
            assertEquals(AccessMode.RESTRICTED, ready.accessMode)
            assertTrue(ready.skipInbox)
        }

    @Test
    fun `loadLibrary initial failure transitions to Error`() =
        runTest {
            val adminRepository: AdminRepository = mock()
            everySuspend { adminRepository.getLibrary("lib-1") } returns networkFailure()

            val viewModel =
                LibrarySettingsViewModel(
                    libraryId = "lib-1",
                    adminRepository = adminRepository,
                    errorBus = ErrorBus(),
                )
            advanceUntilIdle()

            assertIs<LibrarySettingsUiState.Error>(viewModel.state.value)
        }

    @Test
    fun `setAccessMode updates state and saves`() =
        runTest {
            val adminRepository: AdminRepository = mock()
            val library = createLibrary(accessMode = AccessMode.OPEN)
            val updatedLibrary = library.copy(accessMode = AccessMode.RESTRICTED)
            everySuspend { adminRepository.getLibrary("lib-1") } returns AppResult.Success(library)
            everySuspend {
                adminRepository.updateLibrary(
                    libraryId = "lib-1",
                    accessMode = AccessMode.RESTRICTED,
                )
            } returns AppResult.Success(updatedLibrary)

            val viewModel =
                LibrarySettingsViewModel(
                    libraryId = "lib-1",
                    adminRepository = adminRepository,
                    errorBus = ErrorBus(),
                )
            advanceUntilIdle()

            viewModel.setAccessMode(AccessMode.RESTRICTED)
            advanceUntilIdle()

            val ready = assertIs<LibrarySettingsUiState.Ready>(viewModel.state.value)
            assertEquals(AccessMode.RESTRICTED, ready.accessMode)
            verifySuspend(VerifyMode.atLeast(1)) {
                adminRepository.updateLibrary(libraryId = "lib-1", accessMode = AccessMode.RESTRICTED)
            }
        }

    @Test
    fun `toggleSkipInbox updates state and saves`() =
        runTest {
            val adminRepository: AdminRepository = mock()
            val library = createLibrary(skipInbox = false)
            val updatedLibrary = library.copy(skipInbox = true)
            everySuspend { adminRepository.getLibrary("lib-1") } returns AppResult.Success(library)
            everySuspend {
                adminRepository.updateLibrary(
                    libraryId = "lib-1",
                    skipInbox = true,
                )
            } returns AppResult.Success(updatedLibrary)

            val viewModel =
                LibrarySettingsViewModel(
                    libraryId = "lib-1",
                    adminRepository = adminRepository,
                    errorBus = ErrorBus(),
                )
            advanceUntilIdle()

            viewModel.toggleSkipInbox()
            advanceUntilIdle()

            val ready = assertIs<LibrarySettingsUiState.Ready>(viewModel.state.value)
            assertTrue(ready.skipInbox)
            verifySuspend(VerifyMode.atLeast(1)) {
                adminRepository.updateLibrary(libraryId = "lib-1", skipInbox = true)
            }
        }

    @Test
    fun `update failure shows error and reverts state`() =
        runTest {
            val adminRepository: AdminRepository = mock()
            val library = createLibrary(accessMode = AccessMode.OPEN)
            everySuspend { adminRepository.getLibrary("lib-1") } returns AppResult.Success(library)
            everySuspend {
                adminRepository.updateLibrary(
                    libraryId = "lib-1",
                    accessMode = AccessMode.RESTRICTED,
                )
            } returns networkFailure()

            val viewModel =
                LibrarySettingsViewModel(
                    libraryId = "lib-1",
                    adminRepository = adminRepository,
                    errorBus = ErrorBus(),
                )
            advanceUntilIdle()

            viewModel.setAccessMode(AccessMode.RESTRICTED)
            advanceUntilIdle()

            // Should revert to original state on error; transient refresh failure stays in Ready.
            val ready = assertIs<LibrarySettingsUiState.Ready>(viewModel.state.value)
            assertEquals(AccessMode.OPEN, ready.accessMode)
            assertTrue(ready.error != null)
        }

    @Test
    fun `clearError clears error on Ready`() =
        runTest {
            val adminRepository: AdminRepository = mock()
            val library = createLibrary(accessMode = AccessMode.OPEN)
            everySuspend { adminRepository.getLibrary("lib-1") } returns AppResult.Success(library)
            everySuspend {
                adminRepository.updateLibrary(
                    libraryId = "lib-1",
                    accessMode = AccessMode.RESTRICTED,
                )
            } returns networkFailure()

            val viewModel =
                LibrarySettingsViewModel(
                    libraryId = "lib-1",
                    adminRepository = adminRepository,
                    errorBus = ErrorBus(),
                )
            advanceUntilIdle()

            // Trigger a transient refresh failure so Ready has a non-null error.
            viewModel.setAccessMode(AccessMode.RESTRICTED)
            advanceUntilIdle()
            val withError = assertIs<LibrarySettingsUiState.Ready>(viewModel.state.value)
            assertTrue(withError.error != null)

            viewModel.clearError()

            val cleared = assertIs<LibrarySettingsUiState.Ready>(viewModel.state.value)
            assertNull(cleared.error)
        }

    @Test
    fun `setAccessMode is no-op when mode unchanged`() =
        runTest {
            val adminRepository: AdminRepository = mock()
            val library = createLibrary(accessMode = AccessMode.OPEN)
            everySuspend { adminRepository.getLibrary("lib-1") } returns AppResult.Success(library)

            val viewModel =
                LibrarySettingsViewModel(
                    libraryId = "lib-1",
                    adminRepository = adminRepository,
                    errorBus = ErrorBus(),
                )
            advanceUntilIdle()

            viewModel.setAccessMode(AccessMode.OPEN)
            advanceUntilIdle()

            // Still in Ready, still OPEN, no isSaving overlay, no repo call.
            val ready = assertIs<LibrarySettingsUiState.Ready>(viewModel.state.value)
            assertEquals(AccessMode.OPEN, ready.accessMode)
            assertFalse(ready.isSaving)
        }
}
