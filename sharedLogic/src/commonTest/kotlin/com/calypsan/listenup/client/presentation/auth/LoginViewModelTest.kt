package com.calypsan.listenup.client.presentation.auth

import app.cash.turbine.test
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.client.core.ValidationField
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.usecase.auth.LoginUseCase
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

private fun fakeUser(): User =
    User(
        id = UserId("user-1"),
        email = "alice@example.com",
        displayName = "Alice Anderson",
        firstName = "Alice",
        lastName = "Anderson",
        isAdmin = false,
        createdAtMs = 0L,
        updatedAtMs = 0L,
    )

/**
 * Tests for [LoginViewModel] — folds typed [AppResult] over the contract's
 * [AuthError] hierarchy into [LoginUiState] transitions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest :
    FunSpec({

        val testDispatcher = StandardTestDispatcher()

        beforeTest { Dispatchers.setMain(testDispatcher) }
        afterTest { Dispatchers.resetMain() }

        test("initial state is Idle") {
            val useCase = mock<LoginUseCase>()
            val vm = LoginViewModel(useCase)

            vm.state.value.shouldBeInstanceOf<LoginUiState.Idle>()
        }

        test("successful login transitions Idle to Loading to Success") {
            runTest(testDispatcher) {
                val useCase = mock<LoginUseCase>()
                everySuspend { useCase(any(), any()) } returns AppResult.Success(fakeUser())
                val vm = LoginViewModel(useCase)

                vm.state.test {
                    awaitItem().shouldBeInstanceOf<LoginUiState.Idle>()
                    vm.onLoginSubmit("alice@example.com", "password123")
                    awaitItem().shouldBeInstanceOf<LoginUiState.Loading>()
                    awaitItem().shouldBeInstanceOf<LoginUiState.Success>()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("InvalidCredentials maps to LoginErrorType.InvalidCredentials") {
            runTest(testDispatcher) {
                val useCase = mock<LoginUseCase>()
                everySuspend { useCase(any(), any()) } returns
                    AppResult.Failure(AuthError.InvalidCredentials())
                val vm = LoginViewModel(useCase)

                vm.onLoginSubmit("alice@example.com", "password123")
                testDispatcher.scheduler.advanceUntilIdle()

                val error = vm.state.value.shouldBeInstanceOf<LoginUiState.Error>()
                error.type.shouldBeInstanceOf<LoginErrorType.InvalidCredentials>()
            }
        }

        test("AccountDenied also maps to InvalidCredentials (no info leak)") {
            runTest(testDispatcher) {
                val useCase = mock<LoginUseCase>()
                everySuspend { useCase(any(), any()) } returns
                    AppResult.Failure(AuthError.AccountDenied())
                val vm = LoginViewModel(useCase)

                vm.onLoginSubmit("alice@example.com", "password123")
                testDispatcher.scheduler.advanceUntilIdle()

                val error = vm.state.value.shouldBeInstanceOf<LoginUiState.Error>()
                error.type.shouldBeInstanceOf<LoginErrorType.InvalidCredentials>()
            }
        }

        test("RateLimited carries retry-after seconds in detail message") {
            runTest(testDispatcher) {
                val useCase = mock<LoginUseCase>()
                everySuspend { useCase(any(), any()) } returns
                    AppResult.Failure(AuthError.RateLimited(retryAfterSeconds = 30))
                val vm = LoginViewModel(useCase)

                vm.onLoginSubmit("alice@example.com", "password123")
                testDispatcher.scheduler.advanceUntilIdle()

                val error = vm.state.value.shouldBeInstanceOf<LoginUiState.Error>()
                val server = error.type.shouldBeInstanceOf<LoginErrorType.ServerError>()
                server.detail shouldBe "Too many attempts; try again in 30s."
            }
        }

        test("ValidationError with field=email highlights EMAIL (no message substring match)") {
            runTest(testDispatcher) {
                val useCase = mock<LoginUseCase>()
                // Opaque message — the typed field, not the text, drives the highlight.
                everySuspend { useCase(any(), any()) } returns
                    AppResult.Failure(ValidationError("That won't work", field = ValidationField.EMAIL))
                val vm = LoginViewModel(useCase)

                vm.onLoginSubmit("invalid", "password123")
                testDispatcher.scheduler.advanceUntilIdle()

                val error = vm.state.value.shouldBeInstanceOf<LoginUiState.Error>()
                val validation = error.type.shouldBeInstanceOf<LoginErrorType.ValidationError>()
                validation.field shouldBe LoginField.EMAIL
            }
        }

        test("ValidationError with field=password highlights PASSWORD (no message substring match)") {
            runTest(testDispatcher) {
                val useCase = mock<LoginUseCase>()
                everySuspend { useCase(any(), any()) } returns
                    AppResult.Failure(ValidationError("That won't work", field = ValidationField.PASSWORD))
                val vm = LoginViewModel(useCase)

                vm.onLoginSubmit("alice@example.com", "short")
                testDispatcher.scheduler.advanceUntilIdle()

                val error = vm.state.value.shouldBeInstanceOf<LoginUiState.Error>()
                val validation = error.type.shouldBeInstanceOf<LoginErrorType.ValidationError>()
                validation.field shouldBe LoginField.PASSWORD
            }
        }

        test("InternalError maps to NetworkError") {
            runTest(testDispatcher) {
                val useCase = mock<LoginUseCase>()
                everySuspend { useCase(any(), any()) } returns
                    AppResult.Failure(InternalError(correlationId = "corr-1"))
                val vm = LoginViewModel(useCase)

                vm.onLoginSubmit("alice@example.com", "password123")
                testDispatcher.scheduler.advanceUntilIdle()

                val error = vm.state.value.shouldBeInstanceOf<LoginUiState.Error>()
                error.type.shouldBeInstanceOf<LoginErrorType.NetworkError>()
            }
        }

        test("clearError resets Error state to Idle") {
            runTest(testDispatcher) {
                val useCase = mock<LoginUseCase>()
                everySuspend { useCase(any(), any()) } returns
                    AppResult.Failure(AuthError.InvalidCredentials())
                val vm = LoginViewModel(useCase)

                vm.onLoginSubmit("alice@example.com", "password123")
                testDispatcher.scheduler.advanceUntilIdle()
                vm.state.value.shouldBeInstanceOf<LoginUiState.Error>()

                vm.clearError()
                vm.state.value.shouldBeInstanceOf<LoginUiState.Idle>()
            }
        }

        test("clearError on Idle state is a no-op") {
            val useCase = mock<LoginUseCase>()
            val vm = LoginViewModel(useCase)

            vm.clearError()
            vm.state.value.shouldBeInstanceOf<LoginUiState.Idle>()
        }
    })
