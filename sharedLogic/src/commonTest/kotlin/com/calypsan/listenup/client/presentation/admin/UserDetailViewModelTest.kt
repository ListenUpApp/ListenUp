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
            permissions = UserPermissions(canShare = canShare),
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
