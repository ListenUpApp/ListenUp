package com.calypsan.listenup.client.presentation.auth

import app.cash.turbine.test
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.dto.auth.WeakPasswordReason
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.usecase.auth.RegisterUseCase
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

/**
 * Tests for [RegisterViewModel] — folds typed [AppResult] over the contract's
 * [AuthError] hierarchy into [RegisterUiState] transitions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RegisterViewModelTest :
    FunSpec({

        val testDispatcher = StandardTestDispatcher()

        beforeTest { Dispatchers.setMain(testDispatcher) }
        afterTest { Dispatchers.resetMain() }

        test("initial state is Idle") {
            val useCase = mock<RegisterUseCase>()
            val vm = RegisterViewModel(useCase)

            vm.state.value.shouldBeInstanceOf<RegisterUiState.Idle>()
        }

        test("PendingApproval result transitions Idle to Loading to Success") {
            runTest(testDispatcher) {
                val useCase = mock<RegisterUseCase>()
                everySuspend { useCase(any(), any(), any(), any()) } returns
                    AppResult.Success(RegisterResult.PendingApproval(UserId("user-1")))
                val vm = RegisterViewModel(useCase)

                vm.state.test {
                    awaitItem().shouldBeInstanceOf<RegisterUiState.Idle>()
                    vm.onRegisterSubmit("alice@example.com", "password123", "Alice", "Anderson")
                    awaitItem().shouldBeInstanceOf<RegisterUiState.Loading>()
                    awaitItem().shouldBeInstanceOf<RegisterUiState.Success>()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("EmailAlreadyExists surfaces a friendly message") {
            runTest(testDispatcher) {
                val useCase = mock<RegisterUseCase>()
                everySuspend { useCase(any(), any(), any(), any()) } returns
                    AppResult.Failure(AuthError.EmailAlreadyExists())
                val vm = RegisterViewModel(useCase)

                vm.onRegisterSubmit("alice@example.com", "password123", "Alice", "Anderson")
                testDispatcher.scheduler.advanceUntilIdle()

                val error = vm.state.value.shouldBeInstanceOf<RegisterUiState.Error>()
                error.message shouldBe "That email is already registered."
            }
        }

        test("RegistrationDisabled surfaces a friendly message") {
            runTest(testDispatcher) {
                val useCase = mock<RegisterUseCase>()
                everySuspend { useCase(any(), any(), any(), any()) } returns
                    AppResult.Failure(AuthError.RegistrationDisabled())
                val vm = RegisterViewModel(useCase)

                vm.onRegisterSubmit("alice@example.com", "password123", "Alice", "Anderson")
                testDispatcher.scheduler.advanceUntilIdle()

                val error = vm.state.value.shouldBeInstanceOf<RegisterUiState.Error>()
                error.message shouldBe "Registration is closed on this server."
            }
        }

        test("WeakPassword surfaces the policy reason") {
            runTest(testDispatcher) {
                val useCase = mock<RegisterUseCase>()
                everySuspend { useCase(any(), any(), any(), any()) } returns
                    AppResult.Failure(AuthError.WeakPassword(reason = WeakPasswordReason.TOO_SHORT))
                val vm = RegisterViewModel(useCase)

                vm.onRegisterSubmit("alice@example.com", "weak", "Alice", "Anderson")
                testDispatcher.scheduler.advanceUntilIdle()

                val error = vm.state.value.shouldBeInstanceOf<RegisterUiState.Error>()
                error.message shouldBe "That password doesn't meet the policy (too_short)."
            }
        }

        test("RateLimited carries retry-after seconds") {
            runTest(testDispatcher) {
                val useCase = mock<RegisterUseCase>()
                everySuspend { useCase(any(), any(), any(), any()) } returns
                    AppResult.Failure(AuthError.RateLimited(retryAfterSeconds = 60))
                val vm = RegisterViewModel(useCase)

                vm.onRegisterSubmit("alice@example.com", "password123", "Alice", "Anderson")
                testDispatcher.scheduler.advanceUntilIdle()

                val error = vm.state.value.shouldBeInstanceOf<RegisterUiState.Error>()
                error.message shouldBe "Too many attempts; try again in 60s."
            }
        }

        test("ValidationError surfaces the use case's message verbatim") {
            runTest(testDispatcher) {
                val useCase = mock<RegisterUseCase>()
                everySuspend { useCase(any(), any(), any(), any()) } returns
                    AppResult.Failure(ValidationError("First name is required"))
                val vm = RegisterViewModel(useCase)

                vm.onRegisterSubmit("alice@example.com", "password123", "", "Anderson")
                testDispatcher.scheduler.advanceUntilIdle()

                val error = vm.state.value.shouldBeInstanceOf<RegisterUiState.Error>()
                error.message shouldBe "First name is required"
            }
        }

        test("InternalError maps to a generic try-again message") {
            runTest(testDispatcher) {
                val useCase = mock<RegisterUseCase>()
                everySuspend { useCase(any(), any(), any(), any()) } returns
                    AppResult.Failure(InternalError(correlationId = "corr-1"))
                val vm = RegisterViewModel(useCase)

                vm.onRegisterSubmit("alice@example.com", "password123", "Alice", "Anderson")
                testDispatcher.scheduler.advanceUntilIdle()

                val error = vm.state.value.shouldBeInstanceOf<RegisterUiState.Error>()
                error.message shouldBe "Something went wrong. Please try again."
            }
        }

        test("clearError resets state to Idle") {
            runTest(testDispatcher) {
                val useCase = mock<RegisterUseCase>()
                everySuspend { useCase(any(), any(), any(), any()) } returns
                    AppResult.Failure(AuthError.EmailAlreadyExists())
                val vm = RegisterViewModel(useCase)

                vm.onRegisterSubmit("alice@example.com", "password123", "Alice", "Anderson")
                testDispatcher.scheduler.advanceUntilIdle()
                vm.state.value.shouldBeInstanceOf<RegisterUiState.Error>()

                vm.clearError()
                vm.state.value.shouldBeInstanceOf<RegisterUiState.Idle>()
            }
        }
    })
