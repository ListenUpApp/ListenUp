package com.calypsan.listenup.client.presentation.admin

import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import com.calypsan.listenup.core.error.ErrorBus

@OptIn(ExperimentalCoroutinesApi::class)
class LibrarySettingsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private fun createLibrary(
        id: String = "lib-1",
        name: String = "Main Library",
    ) = Library(
        id = id,
        name = name,
        metadataPrecedence = "embedded,abs",
        accessMode = AccessMode.OPEN,
        createdByUserId = null,
        createdAt = 0L,
        revision = 1L,
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
            val library = createLibrary()
            everySuspend { adminRepository.getLibrary("lib-1") } returns AppResult.Success(library)

            val viewModel =
                LibrarySettingsViewModel(
                    libraryId = "lib-1",
                    adminRepository = adminRepository,
                    errorBus = ErrorBus(),
                )
            advanceUntilIdle()

            val ready = assertIs<LibrarySettingsUiState.Ready>(viewModel.state.value)
            assertIs<LibrarySettingsUiState.Ready>(viewModel.state.value)
            assert(ready.library == library)
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
    fun `setInboxEnabled calls repository and reflects inboxEnabled in state`() =
        runTest {
            val adminRepository: AdminRepository = mock()
            val library = createLibrary()
            val updatedLibrary = library.copy(inboxEnabled = true)
            everySuspend { adminRepository.getLibrary("lib-1") } returns AppResult.Success(library)
            everySuspend {
                adminRepository.setInboxEnabled(libraryId = "lib-1", enabled = true)
            } returns AppResult.Success(updatedLibrary)

            val viewModel =
                LibrarySettingsViewModel(
                    libraryId = "lib-1",
                    adminRepository = adminRepository,
                    errorBus = ErrorBus(),
                )
            advanceUntilIdle()

            viewModel.setInboxEnabled(true)
            advanceUntilIdle()

            val ready = assertIs<LibrarySettingsUiState.Ready>(viewModel.state.value)
            assertTrue(ready.inboxEnabled)
            verifySuspend(VerifyMode.atLeast(1)) {
                adminRepository.setInboxEnabled(libraryId = "lib-1", enabled = true)
            }
        }

    @Test
    fun `setInboxEnabled failure reverts state and surfaces error`() =
        runTest {
            val adminRepository: AdminRepository = mock()
            val library = createLibrary()
            everySuspend { adminRepository.getLibrary("lib-1") } returns AppResult.Success(library)
            everySuspend {
                adminRepository.setInboxEnabled(libraryId = "lib-1", enabled = true)
            } returns networkFailure()

            val viewModel =
                LibrarySettingsViewModel(
                    libraryId = "lib-1",
                    adminRepository = adminRepository,
                    errorBus = ErrorBus(),
                )
            advanceUntilIdle()

            viewModel.setInboxEnabled(true)
            advanceUntilIdle()

            val ready = assertIs<LibrarySettingsUiState.Ready>(viewModel.state.value)
            assertFalse(ready.inboxEnabled)
            assertTrue(ready.error != null)
        }

    @Test
    fun `clearError clears error on Ready`() =
        runTest {
            val adminRepository: AdminRepository = mock()
            val library = createLibrary()
            everySuspend { adminRepository.getLibrary("lib-1") } returns AppResult.Success(library)
            everySuspend {
                adminRepository.setInboxEnabled(libraryId = "lib-1", enabled = true)
            } returns networkFailure()

            val viewModel =
                LibrarySettingsViewModel(
                    libraryId = "lib-1",
                    adminRepository = adminRepository,
                    errorBus = ErrorBus(),
                )
            advanceUntilIdle()

            // Trigger a transient failure so Ready has a non-null error.
            viewModel.setInboxEnabled(true)
            advanceUntilIdle()
            val withError = assertIs<LibrarySettingsUiState.Ready>(viewModel.state.value)
            assertTrue(withError.error != null)

            viewModel.clearError()

            val cleared = assertIs<LibrarySettingsUiState.Ready>(viewModel.state.value)
            assertTrue(cleared.error == null)
        }
}
