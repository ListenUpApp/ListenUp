package com.calypsan.listenup.client.presentation.auth

import app.cash.turbine.test
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.dto.auth.WeakPasswordReason
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.client.core.ValidationField
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.usecase.auth.SetupUseCase
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

private fun fakeRootUser(): User =
    User(
        id = UserId("user-root"),
        email = "root@example.com",
        displayName = "Root Admin",
        firstName = "Root",
        lastName = "Admin",
        isAdmin = true,
        createdAtMs = 0L,
        updatedAtMs = 0L,
    )

/**
 * Tests for [SetupViewModel] — folds typed [AppResult] over the contract's
 * [AuthError] hierarchy into [SetupUiState] transitions, plus client-side
 * password-confirm validation that runs before the use case is invoked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModelTest :
    FunSpec({

        val testDispatcher = StandardTestDispatcher()

        beforeTest { Dispatchers.setMain(testDispatcher) }
        afterTest { Dispatchers.resetMain() }

        fun newVm(
            useCase: SetupUseCase = mock(),
            authSession: AuthSession = mock(),
        ): SetupViewModel = SetupViewModel(useCase, authSession)

        test("initial state is Idle") {
            newVm().state.value.shouldBeInstanceOf<SetupUiState.Idle>()
        }

        test("password confirm mismatch fails synchronously without invoking use case") {
            val useCase = mock<SetupUseCase>()
            val vm = newVm(useCase = useCase)

            vm.onSetupSubmit(
                firstName = "Root",
                lastName = "Admin",
                email = "root@example.com",
                password = "password123",
                passwordConfirm = "different",
            )

            val error = vm.state.value.shouldBeInstanceOf<SetupUiState.Error>()
            val validation = error.type.shouldBeInstanceOf<SetupErrorType.ValidationError>()
            validation.field shouldBe SetupField.PASSWORD_CONFIRM
        }

        test("successful setup transitions Idle to Loading to Success") {
            runTest(testDispatcher) {
                val useCase = mock<SetupUseCase>()
                everySuspend { useCase(any(), any(), any(), any()) } returns AppResult.Success(fakeRootUser())
                val vm = newVm(useCase = useCase)

                vm.state.test {
                    awaitItem().shouldBeInstanceOf<SetupUiState.Idle>()
                    vm.onSetupSubmit("Root", "Admin", "root@example.com", "password123", "password123")
                    awaitItem().shouldBeInstanceOf<SetupUiState.Loading>()
                    awaitItem().shouldBeInstanceOf<SetupUiState.Success>()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("SetupAlreadyComplete maps to AlreadyConfigured and refreshes server status") {
            runTest(testDispatcher) {
                val useCase = mock<SetupUseCase>()
                val authSession = mock<AuthSession>()
                everySuspend { useCase(any(), any(), any(), any()) } returns
                    AppResult.Failure(AuthError.SetupAlreadyComplete())
                everySuspend { authSession.checkServerStatus() } returns AuthState.NeedsLogin(openRegistration = false)
                val vm = newVm(useCase = useCase, authSession = authSession)

                vm.onSetupSubmit("Root", "Admin", "root@example.com", "password123", "password123")
                testDispatcher.scheduler.advanceUntilIdle()

                val error = vm.state.value.shouldBeInstanceOf<SetupUiState.Error>()
                error.type shouldBe SetupErrorType.AlreadyConfigured
                verifySuspend { authSession.checkServerStatus() }
            }
        }

        test("WeakPassword maps to ValidationError on PASSWORD field") {
            runTest(testDispatcher) {
                val useCase = mock<SetupUseCase>()
                everySuspend { useCase(any(), any(), any(), any()) } returns
                    AppResult.Failure(AuthError.WeakPassword(reason = WeakPasswordReason.TOO_SHORT))
                val vm = newVm(useCase = useCase)

                vm.onSetupSubmit("Root", "Admin", "root@example.com", "weakpass", "weakpass")
                testDispatcher.scheduler.advanceUntilIdle()

                val error = vm.state.value.shouldBeInstanceOf<SetupUiState.Error>()
                val validation = error.type.shouldBeInstanceOf<SetupErrorType.ValidationError>()
                validation.field shouldBe SetupField.PASSWORD
            }
        }

        test("ValidationError with field=firstName highlights FIRST_NAME (no message substring match)") {
            runTest(testDispatcher) {
                val useCase = mock<SetupUseCase>()
                // Deliberately opaque message — the field discriminator, not the text, drives the highlight.
                everySuspend { useCase(any(), any(), any(), any()) } returns
                    AppResult.Failure(ValidationError("That won't work", field = ValidationField.FIRST_NAME))
                val vm = newVm(useCase = useCase)

                vm.onSetupSubmit("", "Admin", "root@example.com", "password123", "password123")
                testDispatcher.scheduler.advanceUntilIdle()

                val error = vm.state.value.shouldBeInstanceOf<SetupUiState.Error>()
                val validation = error.type.shouldBeInstanceOf<SetupErrorType.ValidationError>()
                validation.field shouldBe SetupField.FIRST_NAME
            }
        }

        test("ValidationError with field=email highlights EMAIL (no message substring match)") {
            runTest(testDispatcher) {
                val useCase = mock<SetupUseCase>()
                everySuspend { useCase(any(), any(), any(), any()) } returns
                    AppResult.Failure(ValidationError("That won't work", field = ValidationField.EMAIL))
                val vm = newVm(useCase = useCase)

                vm.onSetupSubmit("Root", "Admin", "invalid", "password123", "password123")
                testDispatcher.scheduler.advanceUntilIdle()

                val error = vm.state.value.shouldBeInstanceOf<SetupUiState.Error>()
                val validation = error.type.shouldBeInstanceOf<SetupErrorType.ValidationError>()
                validation.field shouldBe SetupField.EMAIL
            }
        }

        test("InternalError maps to ServerError") {
            runTest(testDispatcher) {
                val useCase = mock<SetupUseCase>()
                everySuspend { useCase(any(), any(), any(), any()) } returns
                    AppResult.Failure(InternalError(correlationId = "corr-1"))
                val vm = newVm(useCase = useCase)

                vm.onSetupSubmit("Root", "Admin", "root@example.com", "password123", "password123")
                testDispatcher.scheduler.advanceUntilIdle()

                val error = vm.state.value.shouldBeInstanceOf<SetupUiState.Error>()
                error.type shouldBe SetupErrorType.ServerError
            }
        }

        test("clearError resets Error state to Idle") {
            runTest(testDispatcher) {
                val useCase = mock<SetupUseCase>()
                everySuspend { useCase(any(), any(), any(), any()) } returns
                    AppResult.Failure(InternalError())
                val vm = newVm(useCase = useCase)

                vm.onSetupSubmit("Root", "Admin", "root@example.com", "password123", "password123")
                testDispatcher.scheduler.advanceUntilIdle()
                vm.state.value.shouldBeInstanceOf<SetupUiState.Error>()

                vm.clearError()
                vm.state.value.shouldBeInstanceOf<SetupUiState.Idle>()
            }
        }
    })
