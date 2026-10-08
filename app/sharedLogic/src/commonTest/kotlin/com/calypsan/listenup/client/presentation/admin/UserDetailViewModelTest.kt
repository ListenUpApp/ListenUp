package com.calypsan.listenup.client.presentation.admin

import com.calypsan.listenup.api.dto.auth.UserPermissionsPatch
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.domain.model.UserPermissions
import com.calypsan.listenup.client.domain.repository.AdminRepository
import dev.mokkery.answering.returns
import dev.mokkery.answering.sequentiallyReturns
import dev.mokkery.everySuspend
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import com.calypsan.listenup.core.error.ErrorBus

@OptIn(ExperimentalCoroutinesApi::class)
class UserDetailViewModelTest :
    FunSpec({
        val testDispatcher = StandardTestDispatcher()

        fun createUser(
            id: String = "user-1",
            email: String = "test@example.com",
            canEdit: Boolean = true,
        ) = AdminUserInfo(
            id = id,
            email = email,
            displayName = "Test User",
            firstName = "Test",
            lastName = "User",
            isRoot = false,
            role = "member",
            status = "active",
            permissions = UserPermissions(canEditMetadata = canEdit),
            createdAt = "2024-01-01T00:00:00Z",
        )

        fun networkFailure() = AppResult.Failure(TransportError.NetworkUnavailable())

        beforeTest {
            Dispatchers.setMain(testDispatcher)
        }

        afterTest {
            Dispatchers.resetMain()
        }

        test("initial state is Loading") {
            runTest {
                val adminRepository: AdminRepository = mock()
                everySuspend { adminRepository.getUser("user-1") } returns AppResult.Success(createUser())

                val viewModel =
                    UserDetailViewModel(
                        userId = "user-1",
                        adminRepository = adminRepository,
                        errorBus = ErrorBus(),
                    )

                viewModel.state.value.shouldBeInstanceOf<UserDetailUiState.Loading>()
            }
        }

        test("loadUser transitions to Ready with user details") {
            runTest {
                val adminRepository: AdminRepository = mock()
                val user = createUser(canEdit = false)
                everySuspend { adminRepository.getUser("user-1") } returns AppResult.Success(user)

                val viewModel =
                    UserDetailViewModel(
                        userId = "user-1",
                        adminRepository = adminRepository,
                        errorBus = ErrorBus(),
                    )
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<UserDetailUiState.Ready>()
                ready.user shouldBe user
                ready.canEdit shouldBe false
            }
        }

        test("loadUser initial failure transitions to Error") {
            runTest {
                val adminRepository: AdminRepository = mock()
                everySuspend { adminRepository.getUser("user-1") } returns networkFailure()

                val viewModel =
                    UserDetailViewModel(
                        userId = "user-1",
                        adminRepository = adminRepository,
                        errorBus = ErrorBus(),
                    )
                advanceUntilIdle()

                viewModel.state.value.shouldBeInstanceOf<UserDetailUiState.Error>()
            }
        }

        test("toggleCanEdit updates state and saves") {
            // The permission that had no UI at all until #1270: UserPermissionPolicy has gated
            // every metadata mutation on canEdit since V26, but ContractUserMapper dropped the flag
            // and the admin screen only ever offered canShare — so no member could be granted it.
            runTest {
                val adminRepository: AdminRepository = mock()
                val user = createUser(canEdit = false)
                val updatedUser = user.copy(permissions = UserPermissions(canEditMetadata = true))
                everySuspend { adminRepository.getUser("user-1") } returns AppResult.Success(user)
                everySuspend {
                    adminRepository.updateUser(userId = "user-1", permissions = UserPermissionsPatch(canEditMetadata = true))
                } returns AppResult.Success(updatedUser)

                val viewModel =
                    UserDetailViewModel(
                        userId = "user-1",
                        adminRepository = adminRepository,
                        errorBus = ErrorBus(),
                    )
                advanceUntilIdle()

                viewModel.state.value
                    .shouldBeInstanceOf<UserDetailUiState.Ready>()
                    .canEdit shouldBe false

                viewModel.toggleCanEdit()
                advanceUntilIdle()

                viewModel.state.value
                    .shouldBeInstanceOf<UserDetailUiState.Ready>()
                    .canEdit shouldBe true
                verifySuspend(VerifyMode.atLeast(1)) {
                    adminRepository.updateUser(userId = "user-1", permissions = UserPermissionsPatch(canEditMetadata = true))
                }
            }
        }

        test("a failed toggleCanEdit reverts rather than leaving a grant the server never made") {
            // The sharpest edge on an optimistic permission toggle: if the revert is missed, the
            // admin is looking at "Can Edit: on" for a member the server still refuses to let edit.
            runTest {
                val adminRepository: AdminRepository = mock()
                everySuspend { adminRepository.getUser("user-1") } returns
                    AppResult.Success(createUser(canEdit = false))
                everySuspend { adminRepository.updateUser(userId = "user-1", permissions = UserPermissionsPatch(canEditMetadata = true)) } returns
                    networkFailure()

                val viewModel =
                    UserDetailViewModel(
                        userId = "user-1",
                        adminRepository = adminRepository,
                        errorBus = ErrorBus(),
                    )
                advanceUntilIdle()

                viewModel.toggleCanEdit()
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<UserDetailUiState.Ready>()
                ready.canEdit shouldBe false
                ready.isSaving shouldBe false
                (ready.error != null) shouldBe true
            }
        }

        test("a successful save clears the error a failed one left behind") {
            // Web shows Ready.error inline and has no snackbar to acknowledge it, so nothing ever
            // called clearError: a failed toggle's alert stayed on screen after the next toggle
            // saved. The error describes the last save; once a save succeeds it is no longer true.
            runTest {
                val adminRepository: AdminRepository = mock()
                val user = createUser(canEdit = false)
                everySuspend { adminRepository.getUser("user-1") } returns AppResult.Success(user)
                everySuspend { adminRepository.updateUser(userId = "user-1", permissions = UserPermissionsPatch(canEditMetadata = true)) } sequentiallyReturns
                    listOf(
                        networkFailure(),
                        AppResult.Success(user.copy(permissions = UserPermissions(canEditMetadata = true))),
                    )

                val viewModel =
                    UserDetailViewModel(
                        userId = "user-1",
                        adminRepository = adminRepository,
                        errorBus = ErrorBus(),
                    )
                advanceUntilIdle()

                viewModel.toggleCanEdit()
                advanceUntilIdle()
                (
                    viewModel.state.value
                        .shouldBeInstanceOf<UserDetailUiState.Ready>()
                        .error != null
                ) shouldBe true

                viewModel.toggleCanEdit()
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<UserDetailUiState.Ready>()
                ready.canEdit shouldBe true
                ready.error shouldBe null
            }
        }

        test("clearError clears transient Ready error") {
            runTest {
                // Load succeeds so VM reaches Ready; then a toggle failure surfaces a
                // transient error on Ready that clearError resets.
                val adminRepository: AdminRepository = mock()
                val user = createUser(canEdit = true)
                everySuspend { adminRepository.getUser("user-1") } returns AppResult.Success(user)
                everySuspend {
                    adminRepository.updateUser(
                        userId = "user-1",
                        permissions = UserPermissionsPatch(canEditMetadata = false),
                    )
                } returns networkFailure()

                val viewModel =
                    UserDetailViewModel(
                        userId = "user-1",
                        adminRepository = adminRepository,
                        errorBus = ErrorBus(),
                    )
                advanceUntilIdle()

                viewModel.toggleCanEdit()
                advanceUntilIdle()

                val readyWithError = viewModel.state.value.shouldBeInstanceOf<UserDetailUiState.Ready>()
                (readyWithError.error != null) shouldBe true

                viewModel.clearError()

                val readyCleared = viewModel.state.value.shouldBeInstanceOf<UserDetailUiState.Ready>()
                readyCleared.error shouldBe null
            }
        }

        test("protected users cannot have permissions toggled") {
            runTest {
                val adminRepository: AdminRepository = mock()
                val rootUser =
                    AdminUserInfo(
                        id = "root-1",
                        email = "root@example.com",
                        displayName = "Root User",
                        firstName = "Root",
                        lastName = "User",
                        isRoot = true,
                        role = "admin",
                        status = "active",
                        permissions = UserPermissions(canEditMetadata = true),
                        createdAt = "2024-01-01T00:00:00Z",
                    )
                everySuspend { adminRepository.getUser("root-1") } returns AppResult.Success(rootUser)

                val viewModel =
                    UserDetailViewModel(
                        userId = "root-1",
                        adminRepository = adminRepository,
                        errorBus = ErrorBus(),
                    )
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<UserDetailUiState.Ready>()
                ready.isProtected shouldBe true
            }
        }
    })
