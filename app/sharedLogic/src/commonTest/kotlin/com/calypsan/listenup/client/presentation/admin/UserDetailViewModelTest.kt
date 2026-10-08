package com.calypsan.listenup.client.presentation.admin

import com.calypsan.listenup.client.domain.model.AccessLabel
import com.calypsan.listenup.client.test.fake.FakeInstanceRepository
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.domain.model.UserPermissions
import com.calypsan.listenup.client.domain.repository.AdminRepository
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.mock
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
                        instanceRepository = FakeInstanceRepository(),
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
                        instanceRepository = FakeInstanceRepository(),
                        errorBus = ErrorBus(),
                    )
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<UserDetailUiState.Ready>()
                ready.user shouldBe user.copy(access = AccessLabel.LISTENER)
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
                        instanceRepository = FakeInstanceRepository(),
                        errorBus = ErrorBus(),
                    )
                advanceUntilIdle()

                viewModel.state.value.shouldBeInstanceOf<UserDetailUiState.Error>()
            }
        }

        test("Ready names the member by the preset their flags match") {
            runTest {
                val adminRepository: AdminRepository = mock()
                everySuspend { adminRepository.getUser("user-1") } returns
                    AppResult.Success(createUser().copy(permissions = UserPermissions(canEditMetadata = true, canCurateLibrary = true)))
                val viewModel =
                    UserDetailViewModel(
                        userId = "user-1",
                        adminRepository = adminRepository,
                        instanceRepository = FakeInstanceRepository(),
                        errorBus = ErrorBus(),
                    )
                advanceUntilIdle()
                viewModel.state.value
                    .shouldBeInstanceOf<UserDetailUiState.Ready>()
                    .user.access shouldBe AccessLabel.LIBRARIAN
            }
        }

        test("against an older server the label is the role, not a preset") {
            runTest {
                val adminRepository: AdminRepository = mock()
                everySuspend { adminRepository.getUser("user-1") } returns AppResult.Success(createUser())
                val viewModel =
                    UserDetailViewModel(
                        userId = "user-1",
                        adminRepository = adminRepository,
                        instanceRepository = FakeInstanceRepository(setOf("canEdit")),
                        errorBus = ErrorBus(),
                    )
                advanceUntilIdle()
                viewModel.state.value
                    .shouldBeInstanceOf<UserDetailUiState.Ready>()
                    .user.access shouldBe AccessLabel.MEMBER
            }
        }
    })
