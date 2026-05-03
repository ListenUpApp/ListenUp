package com.calypsan.listenup.client.presentation.auth

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.client.checkIs
import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.repository.AuthRepository
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.LoginResult
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
import kotlin.test.assertEquals
import kotlin.test.assertIs
import com.calypsan.listenup.client.core.Success

/**
 * Tests for SetupViewModel.
 *
 * Tests cover:
 * - First name validation (non-blank)
 * - Last name validation (non-blank)
 * - Email validation (@ and . requirements)
 * - Password validation (min 8 chars)
 * - Password confirmation (must match)
 * - Successful setup flow with token/user persistence
 * - Error classification from exceptions
 * - State transitions (Idle -> Loading -> Success/Error)
 * - clearError behavior
 *
 * Uses Mokkery for mocking AuthApiContract, SettingsRepository, and UserDao.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    // ========== Test Fixtures ==========

    private class TestFixture {
        val authRepository: AuthRepository = mock()
        val authSession: AuthSession = mock()
        val userRepository: UserRepository = mock()

        fun build(): SetupViewModel =
            SetupViewModel(
                authRepository = authRepository,
                authSession = authSession,
                userRepository = userRepository,
            )
    }

    private fun createFixture(): TestFixture {
        val fixture = TestFixture()

        // Default stubs for successful operations
        everySuspend { fixture.authSession.saveAuthTokens(any(), any(), any(), any()) } returns Unit
        everySuspend { fixture.userRepository.saveUser(any()) } returns Unit

        return fixture
    }

    // ========== Test Data Factories ==========

    private fun createLoginResult(
        accessToken: String = "access-token-123",
        refreshToken: String = "refresh-token-456",
        sessionId: String = "session-789",
        userId: String = "user-1",
        email: String = "admin@example.com",
    ): LoginResult =
        LoginResult(
            accessToken = AccessToken(accessToken),
            refreshToken = RefreshToken(refreshToken),
            sessionId = sessionId,
            userId = userId,
            user =
                User(
                    id =
                        UserId(userId),
                    email = email,
                    displayName = "Admin User",
                    firstName = "Admin",
                    lastName = "User",
                    isAdmin = true,
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

    // ========== Initial State Tests ==========

    @Test
    fun `initial state is Idle`() =
        runTest {
            // Given
            val fixture = createFixture()
            val viewModel = fixture.build()

            // Then
            assertEquals(SetupUiState.Idle, viewModel.state.value)
        }

    // ========== First Name Validation Tests ==========

    @Test
    fun `setup rejects empty first name`() =
        runTest {
            // Given
            val fixture = createFixture()
            val viewModel = fixture.build()

            // When
            viewModel.onSetupSubmit(
                firstName = "",
                lastName = "User",
                email = "admin@example.com",
                password = "password123",
                passwordConfirm = "password123",
            )
            advanceUntilIdle()

            // Then
            val error = assertIs<SetupUiState.Error>(viewModel.state.value)
            val validation = assertIs<SetupErrorType.ValidationError>(error.type)
            assertEquals(SetupField.FIRST_NAME, validation.field)
        }

    @Test
    fun `setup rejects blank first name`() =
        runTest {
            // Given
            val fixture = createFixture()
            val viewModel = fixture.build()

            // When
            viewModel.onSetupSubmit(
                firstName = "   ",
                lastName = "User",
                email = "admin@example.com",
                password = "password123",
                passwordConfirm = "password123",
            )
            advanceUntilIdle()

            // Then
            val error = assertIs<SetupUiState.Error>(viewModel.state.value)
            val validation = assertIs<SetupErrorType.ValidationError>(error.type)
            assertEquals(SetupField.FIRST_NAME, validation.field)
        }

    // ========== Last Name Validation Tests ==========

    @Test
    fun `setup rejects empty last name`() =
        runTest {
            // Given
            val fixture = createFixture()
            val viewModel = fixture.build()

            // When
            viewModel.onSetupSubmit(
                firstName = "Admin",
                lastName = "",
                email = "admin@example.com",
                password = "password123",
                passwordConfirm = "password123",
            )
            advanceUntilIdle()

            // Then
            val error = assertIs<SetupUiState.Error>(viewModel.state.value)
            val validation = assertIs<SetupErrorType.ValidationError>(error.type)
            assertEquals(SetupField.LAST_NAME, validation.field)
        }

    @Test
    fun `setup rejects blank last name`() =
        runTest {
            // Given
            val fixture = createFixture()
            val viewModel = fixture.build()

            // When
            viewModel.onSetupSubmit(
                firstName = "Admin",
                lastName = "   ",
                email = "admin@example.com",
                password = "password123",
                passwordConfirm = "password123",
            )
            advanceUntilIdle()

            // Then
            val error = assertIs<SetupUiState.Error>(viewModel.state.value)
            val validation = assertIs<SetupErrorType.ValidationError>(error.type)
            assertEquals(SetupField.LAST_NAME, validation.field)
        }

    // ========== Email Validation Tests ==========

    @Test
    fun `setup rejects email without at symbol`() =
        runTest {
            // Given
            val fixture = createFixture()
            val viewModel = fixture.build()

            // When
            viewModel.onSetupSubmit(
                firstName = "Admin",
                lastName = "User",
                email = "invalid.email",
                password = "password123",
                passwordConfirm = "password123",
            )
            advanceUntilIdle()

            // Then
            val error = assertIs<SetupUiState.Error>(viewModel.state.value)
            val validation = assertIs<SetupErrorType.ValidationError>(error.type)
            assertEquals(SetupField.EMAIL, validation.field)
        }

    @Test
    fun `setup rejects email without dot`() =
        runTest {
            // Given
            val fixture = createFixture()
            val viewModel = fixture.build()

            // When
            viewModel.onSetupSubmit(
                firstName = "Admin",
                lastName = "User",
                email = "invalid@email",
                password = "password123",
                passwordConfirm = "password123",
            )
            advanceUntilIdle()

            // Then
            val error = assertIs<SetupUiState.Error>(viewModel.state.value)
            val validation = assertIs<SetupErrorType.ValidationError>(error.type)
            assertEquals(SetupField.EMAIL, validation.field)
        }

    @Test
    fun `setup rejects empty email`() =
        runTest {
            // Given
            val fixture = createFixture()
            val viewModel = fixture.build()

            // When
            viewModel.onSetupSubmit(
                firstName = "Admin",
                lastName = "User",
                email = "",
                password = "password123",
                passwordConfirm = "password123",
            )
            advanceUntilIdle()

            // Then
            val error = assertIs<SetupUiState.Error>(viewModel.state.value)
            val validation = assertIs<SetupErrorType.ValidationError>(error.type)
            assertEquals(SetupField.EMAIL, validation.field)
        }

    // ========== Password Validation Tests ==========

    @Test
    fun `setup rejects password shorter than 8 characters`() =
        runTest {
            // Given
            val fixture = createFixture()
            val viewModel = fixture.build()

            // When
            viewModel.onSetupSubmit(
                firstName = "Admin",
                lastName = "User",
                email = "admin@example.com",
                password = "1234567",
                passwordConfirm = "1234567",
            )
            advanceUntilIdle()

            // Then
            val error = assertIs<SetupUiState.Error>(viewModel.state.value)
            val validation = assertIs<SetupErrorType.ValidationError>(error.type)
            assertEquals(SetupField.PASSWORD, validation.field)
        }

    @Test
    fun `setup accepts password with exactly 8 characters`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.authRepository.setup(any(), any(), any(), any()) } returns createLoginResult()
            val viewModel = fixture.build()

            // When
            viewModel.onSetupSubmit(
                firstName = "Admin",
                lastName = "User",
                email = "admin@example.com",
                password = "12345678",
                passwordConfirm = "12345678",
            )
            advanceUntilIdle()

            // Then - should proceed to API call, not validation error
            checkIs<SetupUiState.Success>(viewModel.state.value)
        }

    @Test
    fun `setup rejects empty password`() =
        runTest {
            // Given
            val fixture = createFixture()
            val viewModel = fixture.build()

            // When
            viewModel.onSetupSubmit(
                firstName = "Admin",
                lastName = "User",
                email = "admin@example.com",
                password = "",
                passwordConfirm = "",
            )
            advanceUntilIdle()

            // Then
            val error = assertIs<SetupUiState.Error>(viewModel.state.value)
            val validation = assertIs<SetupErrorType.ValidationError>(error.type)
            assertEquals(SetupField.PASSWORD, validation.field)
        }

    // ========== Password Confirmation Tests ==========

    @Test
    fun `setup rejects mismatched password confirmation`() =
        runTest {
            // Given
            val fixture = createFixture()
            val viewModel = fixture.build()

            // When
            viewModel.onSetupSubmit(
                firstName = "Admin",
                lastName = "User",
                email = "admin@example.com",
                password = "password123",
                passwordConfirm = "differentpassword",
            )
            advanceUntilIdle()

            // Then
            val error = assertIs<SetupUiState.Error>(viewModel.state.value)
            val validation = assertIs<SetupErrorType.ValidationError>(error.type)
            assertEquals(SetupField.PASSWORD_CONFIRM, validation.field)
        }

    @Test
    fun `setup accepts matching password confirmation`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.authRepository.setup(any(), any(), any(), any()) } returns createLoginResult()
            val viewModel = fixture.build()

            // When
            viewModel.onSetupSubmit(
                firstName = "Admin",
                lastName = "User",
                email = "admin@example.com",
                password = "password123",
                passwordConfirm = "password123",
            )
            advanceUntilIdle()

            // Then
            checkIs<SetupUiState.Success>(viewModel.state.value)
        }

    // ========== Successful Setup Flow Tests ==========

    @Test
    fun `setup saves auth tokens on success`() =
        runTest {
            // Given
            val response =
                createLoginResult(
                    accessToken = "my-access-token",
                    refreshToken = "my-refresh-token",
                    sessionId = "my-session",
                    userId = "admin-42",
                )
            val fixture = createFixture()
            everySuspend { fixture.authRepository.setup(any(), any(), any(), any()) } returns response
            val viewModel = fixture.build()

            // When
            viewModel.onSetupSubmit(
                firstName = "Admin",
                lastName = "User",
                email = "admin@example.com",
                password = "password123",
                passwordConfirm = "password123",
            )
            advanceUntilIdle()

            // Then
            verifySuspend {
                fixture.authSession.saveAuthTokens(
                    access = AccessToken("my-access-token"),
                    refresh = RefreshToken("my-refresh-token"),
                    sessionId = "my-session",
                    userId = "admin-42",
                )
            }
        }

    @Test
    fun `setup persists user data on success`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.authRepository.setup(any(), any(), any(), any()) } returns createLoginResult()
            val viewModel = fixture.build()

            // When
            viewModel.onSetupSubmit(
                firstName = "Admin",
                lastName = "User",
                email = "admin@example.com",
                password = "password123",
                passwordConfirm = "password123",
            )
            advanceUntilIdle()

            // Then
            verifySuspend { fixture.userRepository.saveUser(any<User>()) }
        }

    @Test
    fun `setup transitions to Success on completion`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.authRepository.setup(any(), any(), any(), any()) } returns createLoginResult()
            val viewModel = fixture.build()

            // When
            viewModel.onSetupSubmit(
                firstName = "Admin",
                lastName = "User",
                email = "admin@example.com",
                password = "password123",
                passwordConfirm = "password123",
            )
            advanceUntilIdle()

            // Then
            checkIs<SetupUiState.Success>(viewModel.state.value)
        }

    @Test
    fun `setup trims whitespace from names and email`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.authRepository.setup(any(), any(), any(), any()) } returns createLoginResult()
            val viewModel = fixture.build()

            // When - names and email have leading/trailing spaces
            viewModel.onSetupSubmit(
                firstName = "  Admin  ",
                lastName = "  User  ",
                email = "  admin@example.com  ",
                password = "password123",
                passwordConfirm = "password123",
            )
            advanceUntilIdle()

            // Then - should succeed with trimmed values
            checkIs<SetupUiState.Success>(viewModel.state.value)
            verifySuspend {
                fixture.authRepository.setup(
                    email = "admin@example.com",
                    password = "password123",
                    firstName = "Admin",
                    lastName = "User",
                )
            }
        }

    // ========== Error Classification Tests ==========

    @Test
    fun `setup classifies already configured error`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.authRepository.setup(any(), any(), any(), any()) } throws Exception("Server already configured")
            everySuspend { fixture.authSession.checkServerStatus() } returns
                com.calypsan.listenup.client.domain.model.AuthState
                    .NeedsLogin()
            val viewModel = fixture.build()

            // When
            viewModel.onSetupSubmit(
                firstName = "Admin",
                lastName = "User",
                email = "admin@example.com",
                password = "password123",
                passwordConfirm = "password123",
            )
            advanceUntilIdle()

            // Then
            val error = assertIs<SetupUiState.Error>(viewModel.state.value)
            checkIs<SetupErrorType.AlreadyConfigured>(error.type)
        }

    @Test
    fun `setup triggers auth state refresh on already configured error`() =
        runTest {
            // Given - server returns "already configured" error (409 scenario)
            // This happens when user's setup request timed out but server completed it,
            // leaving user stranded on setup screen unable to proceed.
            val fixture = createFixture()
            everySuspend { fixture.authRepository.setup(any(), any(), any(), any()) } throws Exception("Server already configured")
            everySuspend { fixture.authSession.checkServerStatus() } returns
                com.calypsan.listenup.client.domain.model.AuthState
                    .NeedsLogin()
            val viewModel = fixture.build()

            // When
            viewModel.onSetupSubmit(
                firstName = "Admin",
                lastName = "User",
                email = "admin@example.com",
                password = "password123",
                passwordConfirm = "password123",
            )
            advanceUntilIdle()

            // Then - checkServerStatus should be called to refresh auth state
            // This allows navigation to automatically transition to login screen
            // following the "never stranded" principle
            verifySuspend { fixture.authSession.checkServerStatus() }
        }

    @Test
    fun `setup classifies network error from connection message`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.authRepository.setup(any(), any(), any(), any()) } throws Exception("Connection refused")
            val viewModel = fixture.build()

            // When
            viewModel.onSetupSubmit(
                firstName = "Admin",
                lastName = "User",
                email = "admin@example.com",
                password = "password123",
                passwordConfirm = "password123",
            )
            advanceUntilIdle()

            // Then
            val error = assertIs<SetupUiState.Error>(viewModel.state.value)
            checkIs<SetupErrorType.NetworkError>(error.type)
        }

    @Test
    fun `setup classifies network error from network message`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.authRepository.setup(any(), any(), any(), any()) } throws Exception("Network unavailable")
            val viewModel = fixture.build()

            // When
            viewModel.onSetupSubmit(
                firstName = "Admin",
                lastName = "User",
                email = "admin@example.com",
                password = "password123",
                passwordConfirm = "password123",
            )
            advanceUntilIdle()

            // Then
            val error = assertIs<SetupUiState.Error>(viewModel.state.value)
            checkIs<SetupErrorType.NetworkError>(error.type)
        }

    @Test
    fun `setup classifies unknown error as server error`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.authRepository.setup(any(), any(), any(), any()) } throws Exception("Something unexpected")
            val viewModel = fixture.build()

            // When
            viewModel.onSetupSubmit(
                firstName = "Admin",
                lastName = "User",
                email = "admin@example.com",
                password = "password123",
                passwordConfirm = "password123",
            )
            advanceUntilIdle()

            // Then
            val error = assertIs<SetupUiState.Error>(viewModel.state.value)
            checkIs<SetupErrorType.ServerError>(error.type)
        }

    // ========== clearError Tests ==========

    @Test
    fun `clearError resets Error state to Idle`() =
        runTest {
            // Given - put viewModel in error state
            val fixture = createFixture()
            val viewModel = fixture.build()
            viewModel.onSetupSubmit(
                firstName = "",
                lastName = "User",
                email = "admin@example.com",
                password = "password123",
                passwordConfirm = "password123",
            )
            advanceUntilIdle()
            checkIs<SetupUiState.Error>(viewModel.state.value)

            // When
            viewModel.clearError()

            // Then
            assertEquals(SetupUiState.Idle, viewModel.state.value)
        }

    @Test
    fun `clearError does nothing when not in Error state`() =
        runTest {
            // Given - viewModel in Idle state
            val fixture = createFixture()
            val viewModel = fixture.build()
            assertEquals(SetupUiState.Idle, viewModel.state.value)

            // When
            viewModel.clearError()

            // Then - still Idle
            assertEquals(SetupUiState.Idle, viewModel.state.value)
        }

    @Test
    fun `clearError does nothing when in Success state`() =
        runTest {
            // Given - viewModel in Success state
            val fixture = createFixture()
            everySuspend { fixture.authRepository.setup(any(), any(), any(), any()) } returns createLoginResult()
            val viewModel = fixture.build()
            viewModel.onSetupSubmit(
                firstName = "Admin",
                lastName = "User",
                email = "admin@example.com",
                password = "password123",
                passwordConfirm = "password123",
            )
            advanceUntilIdle()
            checkIs<SetupUiState.Success>(viewModel.state.value)

            // When
            viewModel.clearError()

            // Then - still Success
            checkIs<SetupUiState.Success>(viewModel.state.value)
        }
}
