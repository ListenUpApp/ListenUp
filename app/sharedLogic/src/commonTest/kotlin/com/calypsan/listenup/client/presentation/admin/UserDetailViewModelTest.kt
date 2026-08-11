package com.calypsan.listenup.client.presentation.admin

import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.domain.model.UserPermissions
import com.calypsan.listenup.client.domain.repository.AdminRepository
import dev.mokkery.answering.returns
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
            canShare: Boolean = true,
        ) = AdminUserInfo(
            id = id,
            email = email,
            displayName = "Test User",
            firstName = "Test",
            lastName = "User",
            isRoot = false,
            role = "member",
            status = "active",
            permissions = UserPermissions(canEdit = canEdit, canShare = canShare),
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
                val user = createUser(canShare = false)
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
                ready.canShare shouldBe false
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

        test("toggleCanShare updates state and saves") {
            runTest {
                val adminRepository: AdminRepository = mock()
                val user = createUser(canShare = true)
                val updatedUser =
                    user.copy(
                        permissions = UserPermissions(canShare = false),
                    )
                everySuspend { adminRepository.getUser("user-1") } returns AppResult.Success(user)
                everySuspend {
                    adminRepository.updateUser(
                        userId = "user-1",
                        canShare = false,
                    )
                } returns AppResult.Success(updatedUser)

                val viewModel =
                    UserDetailViewModel(
                        userId = "user-1",
                        adminRepository = adminRepository,
                        errorBus = ErrorBus(),
                    )
                advanceUntilIdle()

                viewModel.toggleCanShare()
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<UserDetailUiState.Ready>()
                ready.canShare shouldBe false
                verifySuspend(VerifyMode.atLeast(1)) {
                    adminRepository.updateUser(userId = "user-1", canShare = false)
                }
            }
        }

        test("toggleCanEdit updates state and saves") {
            // The permission that had no UI at all until #1270: UserPermissionPolicy has gated
            // every metadata mutation on canEdit since V26, but ContractUserMapper dropped the flag
            // and the admin screen only ever offered canShare — so no member could be granted it.
            runTest {
                val adminRepository: AdminRepository = mock()
                val user = createUser(canEdit = false)
                val updatedUser = user.copy(permissions = UserPermissions(canEdit = true, canShare = true))
                everySuspend { adminRepository.getUser("user-1") } returns AppResult.Success(user)
                everySuspend {
                    adminRepository.updateUser(userId = "user-1", canEdit = true)
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
                    adminRepository.updateUser(userId = "user-1", canEdit = true)
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
                everySuspend { adminRepository.updateUser(userId = "user-1", canEdit = true) } returns
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

        test("toggling one permission leaves the other untouched") {
            // The server applies AdminUserPatch.permissions wholesale, so the repository reads the
            // user back to carry the flag that is not moving. This pins the ViewModel half of that
            // contract: a canShare toggle must not disturb the canEdit the screen is showing.
            runTest {
                val adminRepository: AdminRepository = mock()
                val user = createUser(canEdit = false, canShare = true)
                everySuspend { adminRepository.getUser("user-1") } returns AppResult.Success(user)
                everySuspend { adminRepository.updateUser(userId = "user-1", canShare = false) } returns
                    AppResult.Success(user.copy(permissions = UserPermissions(canEdit = false, canShare = false)))

                val viewModel =
                    UserDetailViewModel(
                        userId = "user-1",
                        adminRepository = adminRepository,
                        errorBus = ErrorBus(),
                    )
                advanceUntilIdle()

                viewModel.toggleCanShare()
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<UserDetailUiState.Ready>()
                ready.canShare shouldBe false
                ready.canEdit shouldBe false
            }
        }

        test("clearError clears transient Ready error") {
            runTest {
                // Load succeeds so VM reaches Ready; then a toggle failure surfaces a
                // transient error on Ready that clearError resets.
                val adminRepository: AdminRepository = mock()
                val user = createUser(canShare = true)
                everySuspend { adminRepository.getUser("user-1") } returns AppResult.Success(user)
                everySuspend {
                    adminRepository.updateUser(
                        userId = "user-1",
                        canShare = false,
                    )
                } returns networkFailure()

                val viewModel =
                    UserDetailViewModel(
                        userId = "user-1",
                        adminRepository = adminRepository,
                        errorBus = ErrorBus(),
                    )
                advanceUntilIdle()

                viewModel.toggleCanShare()
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
                        permissions = UserPermissions(canShare = true),
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
