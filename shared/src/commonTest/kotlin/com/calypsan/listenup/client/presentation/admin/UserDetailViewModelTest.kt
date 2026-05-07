package com.calypsan.listenup.client.presentation.admin

import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.domain.model.UserPermissions
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
class UserDetailViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private fun createUser(
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
            everySuspend { adminRepository.getUser("user-1") } returns AppResult.Success(createUser())

            val viewModel =
                UserDetailViewModel(
                    userId = "user-1",
                    adminRepository = adminRepository,
                    errorBus = ErrorBus(),
                )

            assertIs<UserDetailUiState.Loading>(viewModel.state.value)
        }

    @Test
    fun `loadUser transitions to Ready with user details`() =
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

            val ready = assertIs<UserDetailUiState.Ready>(viewModel.state.value)
            assertEquals(user, ready.user)
            assertFalse(ready.canShare)
        }

    @Test
    fun `loadUser initial failure transitions to Error`() =
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

            assertIs<UserDetailUiState.Error>(viewModel.state.value)
        }

    @Test
    fun `toggleCanShare updates state and saves`() =
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

            val ready = assertIs<UserDetailUiState.Ready>(viewModel.state.value)
            assertFalse(ready.canShare)
            verifySuspend(VerifyMode.atLeast(1)) {
                adminRepository.updateUser(userId = "user-1", canShare = false)
            }
        }

    @Test
    fun `clearError clears transient Ready error`() =
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

            val readyWithError = assertIs<UserDetailUiState.Ready>(viewModel.state.value)
            assertTrue(readyWithError.error != null)

            viewModel.clearError()

            val readyCleared = assertIs<UserDetailUiState.Ready>(viewModel.state.value)
            assertNull(readyCleared.error)
        }

    @Test
    fun `protected users cannot have permissions toggled`() =
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

            val ready = assertIs<UserDetailUiState.Ready>(viewModel.state.value)
            assertTrue(ready.isProtected)
        }
}
