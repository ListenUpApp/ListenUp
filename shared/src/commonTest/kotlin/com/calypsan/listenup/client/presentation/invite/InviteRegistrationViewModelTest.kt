package com.calypsan.listenup.client.presentation.invite

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.client.checkIs
import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.client.domain.model.InviteDetails
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.InviteRepository
import com.calypsan.listenup.client.domain.repository.LoginResult
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.domain.repository.UserRepository
import dev.mokkery.answering.returns
import dev.mokkery.answering.throws
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
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class InviteRegistrationViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private val serverUrl = "http://localhost:8080"
    private val inviteCode = "abc123"

    private fun createInviteDetails(valid: Boolean = true) =
        InviteDetails(
            name = "Invited User",
            email = "invited@example.com",
            serverName = "Test Server",
            invitedBy = "Admin User",
            valid = valid,
        )

    private fun createLoginResult() =
        LoginResult(
            accessToken = AccessToken("access-token"),
            refreshToken = RefreshToken("refresh-token"),
            sessionId = "session-id",
            userId = "user-id",
            user =
                User(
                    id =
                        UserId("user-id"),
                    email = "invited@example.com",
                    displayName = "Invited User",
                    firstName = "Invited",
                    lastName = "User",
                    isAdmin = false,
                    createdAtMs = 0L,
                    updatedAtMs = 0L,
                ),
        )

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
            val inviteRepository: InviteRepository = mock()
            val serverConfig: ServerConfig = mock()
            val authSession: AuthSession = mock()
            val userRepository: UserRepository = mock()
            everySuspend { inviteRepository.getInviteDetails(any(), any()) } returns createInviteDetails()

            val viewModel = InviteRegistrationViewModel(inviteRepository, serverConfig, authSession, userRepository, serverUrl, inviteCode)

            checkIs<InviteRegistrationUiState.Loading>(viewModel.state.value)
        }

    @Test
    fun `loadInviteDetails shows Ready state on success`() =
        runTest {
            val inviteRepository: InviteRepository = mock()
            val serverConfig: ServerConfig = mock()
            val authSession: AuthSession = mock()
            val userRepository: UserRepository = mock()
            everySuspend { inviteRepository.getInviteDetails(serverUrl, inviteCode) } returns createInviteDetails()

            val viewModel = InviteRegistrationViewModel(inviteRepository, serverConfig, authSession, userRepository, serverUrl, inviteCode)
            advanceUntilIdle()

            checkIs<InviteRegistrationUiState.Ready>(viewModel.state.value)
        }

    @Test
    fun `loadInviteDetails shows Invalid for invalid invite`() =
        runTest {
            val inviteRepository: InviteRepository = mock()
            val serverConfig: ServerConfig = mock()
            val authSession: AuthSession = mock()
            val userRepository: UserRepository = mock()
            everySuspend { inviteRepository.getInviteDetails(serverUrl, inviteCode) } returns createInviteDetails(valid = false)

            val viewModel = InviteRegistrationViewModel(inviteRepository, serverConfig, authSession, userRepository, serverUrl, inviteCode)
            advanceUntilIdle()

            checkIs<InviteRegistrationUiState.Invalid>(viewModel.state.value)
        }

    @Test
    fun `loadInviteDetails shows LoadError on network failure`() =
        runTest {
            val inviteRepository: InviteRepository = mock()
            val serverConfig: ServerConfig = mock()
            val authSession: AuthSession = mock()
            val userRepository: UserRepository = mock()
            everySuspend { inviteRepository.getInviteDetails(any(), any()) } throws RuntimeException("Network error")

            val viewModel = InviteRegistrationViewModel(inviteRepository, serverConfig, authSession, userRepository, serverUrl, inviteCode)
            advanceUntilIdle()

            checkIs<InviteRegistrationUiState.LoadError>(viewModel.state.value)
        }

    @Test
    fun `submitRegistration validates password length`() =
        runTest {
            val inviteRepository: InviteRepository = mock()
            val serverConfig: ServerConfig = mock()
            val authSession: AuthSession = mock()
            val userRepository: UserRepository = mock()
            everySuspend { inviteRepository.getInviteDetails(any(), any()) } returns createInviteDetails()

            val viewModel = InviteRegistrationViewModel(inviteRepository, serverConfig, authSession, userRepository, serverUrl, inviteCode)
            advanceUntilIdle()

            viewModel.submitRegistration("short", "short")
            advanceUntilIdle()

            val state = assertIs<InviteRegistrationUiState.SubmitError>(viewModel.state.value)
            checkIs<InviteErrorType.ValidationError>(state.errorType)
        }

    @Test
    fun `submitRegistration validates password match`() =
        runTest {
            val inviteRepository: InviteRepository = mock()
            val serverConfig: ServerConfig = mock()
            val authSession: AuthSession = mock()
            val userRepository: UserRepository = mock()
            everySuspend { inviteRepository.getInviteDetails(any(), any()) } returns createInviteDetails()

            val viewModel = InviteRegistrationViewModel(inviteRepository, serverConfig, authSession, userRepository, serverUrl, inviteCode)
            advanceUntilIdle()

            viewModel.submitRegistration("password123", "different123")
            advanceUntilIdle()

            val state = assertIs<InviteRegistrationUiState.SubmitError>(viewModel.state.value)
            checkIs<InviteErrorType.PasswordMismatch>(state.errorType)
        }

    @Test
    fun `submitRegistration stores tokens on success`() =
        runTest {
            val inviteRepository: InviteRepository = mock()
            val serverConfig: ServerConfig = mock()
            val authSession: AuthSession = mock()
            val userRepository: UserRepository = mock()
            everySuspend { inviteRepository.getInviteDetails(any(), any()) } returns createInviteDetails()
            everySuspend { inviteRepository.claimInvite(serverUrl, inviteCode, "password123") } returns createLoginResult()
            everySuspend { serverConfig.setServerUrl(any()) } returns Unit
            everySuspend { authSession.saveAuthTokens(any(), any(), any(), any()) } returns Unit
            everySuspend { userRepository.saveUser(any()) } returns Unit

            val viewModel = InviteRegistrationViewModel(inviteRepository, serverConfig, authSession, userRepository, serverUrl, inviteCode)
            advanceUntilIdle()

            viewModel.submitRegistration("password123", "password123")
            advanceUntilIdle()

            checkIs<InviteRegistrationUiState.Submitted>(viewModel.state.value)
            verifySuspend { authSession.saveAuthTokens(any(), any(), any(), any()) }
        }

    @Test
    fun `clearError returns to Ready`() =
        runTest {
            val inviteRepository: InviteRepository = mock()
            val serverConfig: ServerConfig = mock()
            val authSession: AuthSession = mock()
            val userRepository: UserRepository = mock()
            everySuspend { inviteRepository.getInviteDetails(any(), any()) } returns createInviteDetails()

            val viewModel = InviteRegistrationViewModel(inviteRepository, serverConfig, authSession, userRepository, serverUrl, inviteCode)
            advanceUntilIdle()

            viewModel.submitRegistration("short", "short")
            advanceUntilIdle()
            checkIs<InviteRegistrationUiState.SubmitError>(viewModel.state.value)

            viewModel.clearError()

            checkIs<InviteRegistrationUiState.Ready>(viewModel.state.value)
        }
}
