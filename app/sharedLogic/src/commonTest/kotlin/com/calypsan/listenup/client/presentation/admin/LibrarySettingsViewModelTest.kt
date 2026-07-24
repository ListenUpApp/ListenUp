package com.calypsan.listenup.client.presentation.admin

import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.AccessMode
import com.calypsan.listenup.client.domain.model.Library
import com.calypsan.listenup.client.domain.model.LibraryFolderRef
import com.calypsan.listenup.client.domain.repository.AdminRepository
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import app.cash.turbine.test
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class LibrarySettingsViewModelTest :
    FunSpec({
        val dispatcher = StandardTestDispatcher()
        beforeSpec { Dispatchers.setMain(dispatcher) }
        afterSpec { Dispatchers.resetMain() }

        fun createLibrary(
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

        fun networkFailure() = AppResult.Failure(TransportError.NetworkUnavailable())

        test("initial state is Loading") {
            runTest(dispatcher) {
                val adminRepository: AdminRepository = mock()
                everySuspend { adminRepository.getLibrary() } returns AppResult.Success(createLibrary())

                val viewModel =
                    LibrarySettingsViewModel(
                        adminRepository = adminRepository,
                        errorBus = ErrorBus(),
                    )

                viewModel.state.value.shouldBeInstanceOf<LibrarySettingsUiState.Loading>()
            }
        }

        test("loadLibrary transitions to Ready with library details") {
            runTest(dispatcher) {
                val adminRepository: AdminRepository = mock()
                val library = createLibrary()
                everySuspend { adminRepository.getLibrary() } returns AppResult.Success(library)

                val viewModel =
                    LibrarySettingsViewModel(
                        adminRepository = adminRepository,
                        errorBus = ErrorBus(),
                    )
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<LibrarySettingsUiState.Ready>()
                (ready.library == library) shouldBe true
            }
        }

        test("loadLibrary initial failure transitions to Error") {
            runTest(dispatcher) {
                val adminRepository: AdminRepository = mock()
                everySuspend { adminRepository.getLibrary() } returns networkFailure()

                val viewModel =
                    LibrarySettingsViewModel(
                        adminRepository = adminRepository,
                        errorBus = ErrorBus(),
                    )
                advanceUntilIdle()

                viewModel.state.value.shouldBeInstanceOf<LibrarySettingsUiState.Error>()
            }
        }

        test("addScanPath saves the folder and clears the saving overlay") {
            runTest(dispatcher) {
                val adminRepository: AdminRepository = mock()
                val initial = createLibrary()
                val withFolder =
                    initial.copy(folders = listOf(LibraryFolderRef(id = "f0", rootPath = "/audiobooks")))
                everySuspend { adminRepository.getLibrary() } returns AppResult.Success(initial)
                everySuspend { adminRepository.addScanPath("/audiobooks") } returns AppResult.Success(withFolder)

                val viewModel =
                    LibrarySettingsViewModel(
                        adminRepository = adminRepository,
                        errorBus = ErrorBus(),
                    )
                advanceUntilIdle()

                viewModel.addScanPath("/audiobooks")
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<LibrarySettingsUiState.Ready>()
                ready.library.folders.map { it.rootPath } shouldBe listOf("/audiobooks")
                ready.isSaving shouldBe false
            }
        }

        test("addScanPath emits FolderSavedScanStarted so the screen can confirm the scan") {
            runTest(dispatcher) {
                val adminRepository: AdminRepository = mock()
                val library = createLibrary()
                everySuspend { adminRepository.getLibrary() } returns AppResult.Success(library)
                everySuspend { adminRepository.addScanPath("/audiobooks") } returns AppResult.Success(library)

                val viewModel =
                    LibrarySettingsViewModel(
                        adminRepository = adminRepository,
                        errorBus = ErrorBus(),
                    )
                advanceUntilIdle()

                viewModel.events.test {
                    viewModel.addScanPath("/audiobooks")
                    awaitItem() shouldBe LibrarySettingsEvent.FolderSavedScanStarted
                }
            }
        }

        test("clearError clears error on Ready") {
            runTest(dispatcher) {
                val adminRepository: AdminRepository = mock()
                val library = createLibrary()
                everySuspend { adminRepository.getLibrary() } returns AppResult.Success(library)

                val viewModel =
                    LibrarySettingsViewModel(
                        adminRepository = adminRepository,
                        errorBus = ErrorBus(),
                    )
                advanceUntilIdle()

                viewModel.clearError()

                val cleared = viewModel.state.value.shouldBeInstanceOf<LibrarySettingsUiState.Ready>()
                (cleared.error == null) shouldBe true
            }
        }
    })
