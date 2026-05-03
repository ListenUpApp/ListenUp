package com.calypsan.listenup.client.presentation.auth

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.client.checkIs
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.usecase.auth.LoginUseCase
import dev.mokkery.answering.returns
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
import com.calypsan.listenup.client.core.validationError

/**
 * Tests for LoginViewModel.
 *
 * Tests cover:
 * - Delegation to LoginUseCase
 * - State transitions (Idle -> Loading -> Success/Error)
 * - Error mapping from use case failures
 * - clearError behavior
 *
 * Uses Mokkery for mocking LoginUseCase.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    // ========== Test Fixtures ==========

    private class TestFixture {
        val loginUseCase: LoginUseCase = mock()

        fun build(): LoginViewModel =
            LoginViewModel(
                loginUseCase = loginUseCase,
            )
    }

    private fun createFixture(): TestFixture = TestFixture()

    // ========== Test Data Factories ==========

    private fun createUser(
        id: String = "user-1",
        email: String = "test@example.com",
    ): User =
        User(
            id =
                UserId(id),
            email = email,
            displayName = "Test User",
            firstName = "Test",
            lastName = "User",
            isAdmin = false,
            avatarType = "auto",
            avatarValue = null,
            avatarColor = "#6B7280",
            tagline = null,
            createdAtMs = 1704067200000L,
            updatedAtMs = 1704067200000L,
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
            assertEquals(LoginUiState.Idle, viewModel.state.value)
        }

    // ========== Email Validation Tests ==========

    @Test
    fun `login rejects email without at symbol`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.loginUseCase(any(), any()) } returns
                validationError("Invalid email format")
            val viewModel = fixture.build()

            // When
            viewModel.onLoginSubmit(email = "invalid.email", password = "password123")
            advanceUntilIdle()

            // Then
            val error = assertIs<LoginUiState.Error>(viewModel.state.value)
            val validation = assertIs<LoginErrorType.ValidationError>(error.type)
            assertEquals(LoginField.EMAIL, validation.field)
        }

    @Test
    fun `login rejects empty email`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.loginUseCase(any(), any()) } returns
                validationError("Invalid email format")
            val viewModel = fixture.build()

            // When
            viewModel.onLoginSubmit(email = "", password = "password123")
            advanceUntilIdle()

            // Then
            val error = assertIs<LoginUiState.Error>(viewModel.state.value)
            val validation = assertIs<LoginErrorType.ValidationError>(error.type)
            assertEquals(LoginField.EMAIL, validation.field)
        }

    @Test
    fun `login accepts valid email with at and dot`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.loginUseCase(any(), any()) } returns Success(createUser())
            val viewModel = fixture.build()

            // When
            viewModel.onLoginSubmit(email = "user@example.com", password = "password123")
            advanceUntilIdle()

            // Then - should proceed to API call, not validation error
            checkIs<LoginUiState.Success>(viewModel.state.value)
        }

    @Test
    fun `login passes credentials to use case`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.loginUseCase(any(), any()) } returns Success(createUser())
            val viewModel = fixture.build()

            // When
            viewModel.onLoginSubmit(email = "user@example.com", password = "password123")
            advanceUntilIdle()

            // Then
            verifySuspend { fixture.loginUseCase("user@example.com", "password123") }
        }

    // ========== Password Validation Tests ==========

    @Test
    fun `login rejects empty password`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.loginUseCase(any(), any()) } returns
                validationError("Password is required")
            val viewModel = fixture.build()

            // When
            viewModel.onLoginSubmit(email = "user@example.com", password = "")
            advanceUntilIdle()

            // Then
            val error = assertIs<LoginUiState.Error>(viewModel.state.value)
            val validation = assertIs<LoginErrorType.ValidationError>(error.type)
            assertEquals(LoginField.PASSWORD, validation.field)
        }

    @Test
    fun `login accepts non-empty password`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.loginUseCase(any(), any()) } returns Success(createUser())
            val viewModel = fixture.build()

            // When
            viewModel.onLoginSubmit(email = "user@example.com", password = "x")
            advanceUntilIdle()

            // Then - should proceed (single char password is valid)
            checkIs<LoginUiState.Success>(viewModel.state.value)
        }

    // ========== Successful Login Flow Tests ==========

    @Test
    fun `login transitions to Success on completion`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.loginUseCase(any(), any()) } returns Success(createUser())
            val viewModel = fixture.build()

            // When
            viewModel.onLoginSubmit(email = "user@example.com", password = "password123")
            advanceUntilIdle()

            // Then
            checkIs<LoginUiState.Success>(viewModel.state.value)
        }

    // ========== Error Classification Tests ==========

    @Test
    fun `login classifies invalid credentials error`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.loginUseCase(any(), any()) } returns
                Failure(Exception("invalid credentials"))
            val viewModel = fixture.build()

            // When
            viewModel.onLoginSubmit(email = "user@example.com", password = "wrong")
            advanceUntilIdle()

            // Then
            val error = assertIs<LoginUiState.Error>(viewModel.state.value)
            checkIs<LoginErrorType.InvalidCredentials>(error.type)
        }

    @Test
    fun `login classifies connection refused as network error`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.loginUseCase(any(), any()) } returns
                Failure(Exception("Connection refused"))
            val viewModel = fixture.build()

            // When
            viewModel.onLoginSubmit(email = "user@example.com", password = "password")
            advanceUntilIdle()

            // Then
            val error = assertIs<LoginUiState.Error>(viewModel.state.value)
            val networkError = assertIs<LoginErrorType.NetworkError>(error.type)
            assertEquals("Connection refused. Is the server running?", networkError.detail)
        }

    @Test
    fun `login classifies timeout as network error`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.loginUseCase(any(), any()) } returns
                Failure(Exception("Connection timed out"))
            val viewModel = fixture.build()

            // When
            viewModel.onLoginSubmit(email = "user@example.com", password = "password")
            advanceUntilIdle()

            // Then
            val error = assertIs<LoginUiState.Error>(viewModel.state.value)
            val networkError = assertIs<LoginErrorType.NetworkError>(error.type)
            assertEquals("Connection timed out. Check server address.", networkError.detail)
        }

    @Test
    fun `login classifies unknown host as network error`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.loginUseCase(any(), any()) } returns
                Failure(Exception("Unable to resolve host"))
            val viewModel = fixture.build()

            // When
            viewModel.onLoginSubmit(email = "user@example.com", password = "password")
            advanceUntilIdle()

            // Then
            val error = assertIs<LoginUiState.Error>(viewModel.state.value)
            val networkError = assertIs<LoginErrorType.NetworkError>(error.type)
            assertEquals("Server not found. Check the address.", networkError.detail)
        }

    @Test
    fun `login classifies 500 as server error`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.loginUseCase(any(), any()) } returns
                Failure(Exception("HTTP 500 Internal Server Error"))
            val viewModel = fixture.build()

            // When
            viewModel.onLoginSubmit(email = "user@example.com", password = "password")
            advanceUntilIdle()

            // Then
            val error = assertIs<LoginUiState.Error>(viewModel.state.value)
            val serverError = assertIs<LoginErrorType.ServerError>(error.type)
            assertEquals("Server error (500)", serverError.detail)
        }

    @Test
    fun `login classifies unknown error as server error with message`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.loginUseCase(any(), any()) } returns
                Failure(Exception("Something unexpected happened"))
            val viewModel = fixture.build()

            // When
            viewModel.onLoginSubmit(email = "user@example.com", password = "password")
            advanceUntilIdle()

            // Then
            val error = assertIs<LoginUiState.Error>(viewModel.state.value)
            val serverError = assertIs<LoginErrorType.ServerError>(error.type)
            assertEquals("Something unexpected happened", serverError.detail)
        }

    // ========== clearError Tests ==========

    @Test
    fun `clearError resets Error state to Idle`() =
        runTest {
            // Given - put viewModel in error state
            val fixture = createFixture()
            everySuspend { fixture.loginUseCase(any(), any()) } returns
                Failure(Exception("error"))
            val viewModel = fixture.build()
            viewModel.onLoginSubmit(email = "user@example.com", password = "password")
            advanceUntilIdle()
            checkIs<LoginUiState.Error>(viewModel.state.value)

            // When
            viewModel.clearError()

            // Then
            assertEquals(LoginUiState.Idle, viewModel.state.value)
        }

    @Test
    fun `clearError does nothing when not in Error state`() =
        runTest {
            // Given - viewModel in Idle state
            val fixture = createFixture()
            val viewModel = fixture.build()
            assertEquals(LoginUiState.Idle, viewModel.state.value)

            // When
            viewModel.clearError()

            // Then - still Idle
            assertEquals(LoginUiState.Idle, viewModel.state.value)
        }

    @Test
    fun `clearError does nothing when in Success state`() =
        runTest {
            // Given - viewModel in Success state
            val fixture = createFixture()
            everySuspend { fixture.loginUseCase(any(), any()) } returns Success(createUser())
            val viewModel = fixture.build()
            viewModel.onLoginSubmit(email = "user@example.com", password = "password")
            advanceUntilIdle()
            checkIs<LoginUiState.Success>(viewModel.state.value)

            // When
            viewModel.clearError()

            // Then - still Success
            checkIs<LoginUiState.Success>(viewModel.state.value)
        }
}
