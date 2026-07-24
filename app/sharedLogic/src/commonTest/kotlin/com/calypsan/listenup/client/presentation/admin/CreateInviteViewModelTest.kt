package com.calypsan.listenup.client.presentation.admin

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.checkIs
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.domain.model.InviteInfo
import com.calypsan.listenup.client.domain.usecase.admin.CreateInviteUseCase
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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class CreateInviteViewModelTest :
    FunSpec({
        val testDispatcher = StandardTestDispatcher()

        fun createInviteInfo() =
            InviteInfo(
                id = "invite-1",
                code = "XYZ789",
                name = "New User",
                email = "new@example.com",
                role = "user",
                expiresAt = "2024-02-01T00:00:00Z",
                claimedAt = null,
                url = "https://example.com/invite/XYZ789",
                createdAt = "2024-01-01T00:00:00Z",
            )

        beforeTest {
            Dispatchers.setMain(testDispatcher)
        }

        afterTest {
            Dispatchers.resetMain()
        }

        test("initial state is Idle") {
            runTest {
                val createInviteUseCase: CreateInviteUseCase = mock()
                val viewModel = CreateInviteViewModel(createInviteUseCase)

                val ready = viewModel.state.value.shouldBeInstanceOf<CreateInviteUiState.Ready>()
                checkIs<CreateInviteStatus.Idle>(ready.status)
            }
        }

        test("createInvite validates invalid email") {
            runTest {
                val createInviteUseCase: CreateInviteUseCase = mock()
                // The VM sub-classifies a ValidationError by its typed `field` discriminator, not the
                // message text — so an opaque message still routes to the EMAIL field highlight.
                everySuspend { createInviteUseCase(any(), any(), any()) } returns
                    AppResult.Failure(
                        com.calypsan.listenup.api.error
                            .ValidationError(message = "That won't work", field = "email"),
                    )
                val viewModel = CreateInviteViewModel(createInviteUseCase)

                viewModel.createInvite(email = "invalid-email", role = "user", expiresInDays = 7)
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<CreateInviteUiState.Ready>()
                val status = ready.status.shouldBeInstanceOf<CreateInviteStatus.Error>()
                val errorType = status.type.shouldBeInstanceOf<CreateInviteErrorType.ValidationError>()
                errorType.field shouldBe CreateInviteField.EMAIL
            }
        }

        test("createInvite returns Success with invite on success") {
            runTest {
                val createInviteUseCase: CreateInviteUseCase = mock()
                val invite = createInviteInfo()
                everySuspend { createInviteUseCase(any(), any(), any()) } returns AppResult.Success(invite)
                val viewModel = CreateInviteViewModel(createInviteUseCase)

                viewModel.createInvite(email = "new@example.com", role = "user", expiresInDays = 7)
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<CreateInviteUiState.Ready>()
                val status = ready.status.shouldBeInstanceOf<CreateInviteStatus.Success>()
                status.invite.id shouldBe invite.id
            }
        }

        test("createInvite handles email already exists error") {
            runTest {
                val createInviteUseCase: CreateInviteUseCase = mock()
                // Server returns 409 Conflict for duplicate email — ViewModel routes
                // TransportError.Server4xx(409) → EmailInUse via type-pattern matching.
                everySuspend { createInviteUseCase(any(), any(), any()) } returns
                    AppResult.Failure(
                        com.calypsan.listenup.api.error.TransportError
                            .Server4xx(statusCode = 409),
                    )
                val viewModel = CreateInviteViewModel(createInviteUseCase)

                viewModel.createInvite(email = "test@example.com", role = "user", expiresInDays = 7)
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<CreateInviteUiState.Ready>()
                val status = ready.status.shouldBeInstanceOf<CreateInviteStatus.Error>()
                checkIs<CreateInviteErrorType.EmailInUse>(status.type)
            }
        }

        test("createInvite handles network error") {
            runTest {
                val createInviteUseCase: CreateInviteUseCase = mock()
                // Body-level message convention: TransportError.NetworkUnavailable's
                // body-level message ("No internet connection. Check your network.")
                // contains both "network" and "connection", which the VM's branch
                // looks for.
                everySuspend { createInviteUseCase(any(), any(), any()) } returns
                    AppResult.Failure(
                        com.calypsan.listenup.api.error.TransportError
                            .NetworkUnavailable(),
                    )
                val viewModel = CreateInviteViewModel(createInviteUseCase)

                viewModel.createInvite(email = "test@example.com", role = "user", expiresInDays = 7)
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<CreateInviteUiState.Ready>()
                val status = ready.status.shouldBeInstanceOf<CreateInviteStatus.Error>()
                checkIs<CreateInviteErrorType.NetworkError>(status.type)
            }
        }

        test("server error surfaces the user-facing message, never debugInfo") {
            runTest {
                val createInviteUseCase: CreateInviteUseCase = mock()
                // debugInfo carries per-instance technical detail (and is null-on-wire for guard
                // errors post-#3). The UI must render the constant `message`, never `debugInfo`.
                val serverError =
                    com.calypsan.listenup.api.error.TransportError
                        .Server5xx(statusCode = 500, debugInfo = "SECRET stacktrace /var/lib NPE at Foo.kt:42")
                everySuspend { createInviteUseCase(any(), any(), any()) } returns AppResult.Failure(serverError)
                val viewModel = CreateInviteViewModel(createInviteUseCase)

                viewModel.createInvite(email = "test@example.com", role = "user", expiresInDays = 7)
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<CreateInviteUiState.Ready>()
                val status = ready.status.shouldBeInstanceOf<CreateInviteStatus.Error>()
                val type = status.type.shouldBeInstanceOf<CreateInviteErrorType.ServerError>()
                type.detail shouldBe serverError.message
                (type.detail?.contains("SECRET") == true) shouldBe false
            }
        }

        test("clearError resets to Idle") {
            runTest {
                val createInviteUseCase: CreateInviteUseCase = mock()
                everySuspend { createInviteUseCase(any(), any(), any()) } returns
                    Failure(RuntimeException("Name is required"))
                val viewModel = CreateInviteViewModel(createInviteUseCase)

                viewModel.createInvite(email = "test@example.com", role = "user", expiresInDays = 7)
                advanceUntilIdle()
                val readyAfterError = viewModel.state.value.shouldBeInstanceOf<CreateInviteUiState.Ready>()
                checkIs<CreateInviteStatus.Error>(readyAfterError.status)

                viewModel.clearError()

                val readyAfterClear = viewModel.state.value.shouldBeInstanceOf<CreateInviteUiState.Ready>()
                checkIs<CreateInviteStatus.Idle>(readyAfterClear.status)
            }
        }

        test("reset returns to initial state") {
            runTest {
                val createInviteUseCase: CreateInviteUseCase = mock()
                everySuspend { createInviteUseCase(any(), any(), any()) } returns AppResult.Success(createInviteInfo())
                val viewModel = CreateInviteViewModel(createInviteUseCase)

                viewModel.createInvite(email = "test@example.com", role = "user", expiresInDays = 7)
                advanceUntilIdle()
                val readyAfterSuccess = viewModel.state.value.shouldBeInstanceOf<CreateInviteUiState.Ready>()
                checkIs<CreateInviteStatus.Success>(readyAfterSuccess.status)

                viewModel.reset()

                val readyAfterReset = viewModel.state.value.shouldBeInstanceOf<CreateInviteUiState.Ready>()
                checkIs<CreateInviteStatus.Idle>(readyAfterReset.status)
            }
        }
    })
