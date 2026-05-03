package com.calypsan.listenup.client.presentation.invite

import app.cash.turbine.test
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.client.core.ServerUrl
import com.calypsan.listenup.client.domain.model.InviteDetails
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.repository.InviteRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
import dev.mokkery.answering.returns
import dev.mokkery.answering.throws
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

private const val SERVER_URL = "https://server.example.com"
private const val INVITE_CODE = "abc123"

private fun fakeInviteDetails(valid: Boolean = true): InviteDetails =
    InviteDetails(
        name = "Alice Anderson",
        email = "alice@example.com",
        serverName = "Test Server",
        invitedBy = "Bob",
        valid = valid,
    )

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
 * Tests for [InviteRegistrationViewModel] — drives the two-phase load + submit
 * flow over [InviteRepository]. The repository is exception-shaped (the invite
 * surface is REST-only); the VM swallows non-cancellation throwables, emits
 * to ErrorBus, and maps the message to a typed [InviteErrorType].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InviteRegistrationViewModelTest :
    FunSpec({

        val testDispatcher = StandardTestDispatcher()

        beforeTest { Dispatchers.setMain(testDispatcher) }
        afterTest { Dispatchers.resetMain() }

        fun newVm(
            inviteRepository: InviteRepository = mock(),
            serverConfig: ServerConfig = mock(),
        ): InviteRegistrationViewModel =
            InviteRegistrationViewModel(
                inviteRepository = inviteRepository,
                serverConfig = serverConfig,
                serverUrl = SERVER_URL,
                inviteCode = INVITE_CODE,
            )

        // ========== Load phase ==========

        test("init loads invite details and lands on Ready when valid") {
            runTest(testDispatcher) {
                val invites = mock<InviteRepository>()
                everySuspend { invites.getInviteDetails(SERVER_URL, INVITE_CODE) } returns fakeInviteDetails(valid = true)
                val vm = newVm(inviteRepository = invites)

                testDispatcher.scheduler.advanceUntilIdle()

                val ready = vm.state.value.shouldBeInstanceOf<InviteRegistrationUiState.Ready>()
                ready.details.email shouldBe "alice@example.com"
            }
        }

        test("invalid invite lands on Invalid with friendly message") {
            runTest(testDispatcher) {
                val invites = mock<InviteRepository>()
                everySuspend { invites.getInviteDetails(SERVER_URL, INVITE_CODE) } returns fakeInviteDetails(valid = false)
                val vm = newVm(inviteRepository = invites)

                testDispatcher.scheduler.advanceUntilIdle()

                vm.state.value.shouldBeInstanceOf<InviteRegistrationUiState.Invalid>()
            }
        }

        test("repository throwing during load lands on LoadError") {
            runTest(testDispatcher) {
                val invites = mock<InviteRepository>()
                everySuspend { invites.getInviteDetails(SERVER_URL, INVITE_CODE) } throws RuntimeException("Boom")
                val vm = newVm(inviteRepository = invites)

                testDispatcher.scheduler.advanceUntilIdle()

                val err = vm.state.value.shouldBeInstanceOf<InviteRegistrationUiState.LoadError>()
                err.message shouldBe "Boom"
            }
        }

        // ========== Submit phase ==========

        test("password shorter than minimum surfaces ValidationError(PASSWORD)") {
            runTest(testDispatcher) {
                val invites = mock<InviteRepository>()
                everySuspend { invites.getInviteDetails(SERVER_URL, INVITE_CODE) } returns fakeInviteDetails()
                val vm = newVm(inviteRepository = invites)
                testDispatcher.scheduler.advanceUntilIdle()
                vm.state.value.shouldBeInstanceOf<InviteRegistrationUiState.Ready>()

                vm.submitRegistration(password = "short", confirmPassword = "short")

                val err = vm.state.value.shouldBeInstanceOf<InviteRegistrationUiState.SubmitError>()
                val validation = err.errorType.shouldBeInstanceOf<InviteErrorType.ValidationError>()
                validation.field shouldBe InviteField.PASSWORD
            }
        }

        test("mismatched passwords surface PasswordMismatch") {
            runTest(testDispatcher) {
                val invites = mock<InviteRepository>()
                everySuspend { invites.getInviteDetails(SERVER_URL, INVITE_CODE) } returns fakeInviteDetails()
                val vm = newVm(inviteRepository = invites)
                testDispatcher.scheduler.advanceUntilIdle()

                vm.submitRegistration(password = "password123", confirmPassword = "different1")

                val err = vm.state.value.shouldBeInstanceOf<InviteRegistrationUiState.SubmitError>()
                err.errorType shouldBe InviteErrorType.PasswordMismatch
            }
        }

        test("successful submission transitions Ready to Submitting to Submitted") {
            runTest(testDispatcher) {
                val invites = mock<InviteRepository>()
                val serverConfig = mock<ServerConfig>()
                everySuspend { invites.getInviteDetails(SERVER_URL, INVITE_CODE) } returns fakeInviteDetails()
                everySuspend { serverConfig.setServerUrl(any()) } returns Unit
                everySuspend { invites.claimInvite(SERVER_URL, INVITE_CODE, "password123") } returns fakeUser()
                val vm = newVm(inviteRepository = invites, serverConfig = serverConfig)
                testDispatcher.scheduler.advanceUntilIdle()

                vm.state.test {
                    awaitItem().shouldBeInstanceOf<InviteRegistrationUiState.Ready>()
                    vm.submitRegistration("password123", "password123")
                    awaitItem().shouldBeInstanceOf<InviteRegistrationUiState.Submitting>()
                    awaitItem().shouldBeInstanceOf<InviteRegistrationUiState.Submitted>()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("claimInvite failure with 'already claimed' surfaces InviteInvalid") {
            runTest(testDispatcher) {
                val invites = mock<InviteRepository>()
                val serverConfig = mock<ServerConfig>()
                everySuspend { invites.getInviteDetails(SERVER_URL, INVITE_CODE) } returns fakeInviteDetails()
                everySuspend { serverConfig.setServerUrl(any()) } returns Unit
                everySuspend { invites.claimInvite(any(), any(), any()) } throws RuntimeException("invite already claimed")
                val vm = newVm(inviteRepository = invites, serverConfig = serverConfig)
                testDispatcher.scheduler.advanceUntilIdle()

                vm.submitRegistration("password123", "password123")
                testDispatcher.scheduler.advanceUntilIdle()

                val err = vm.state.value.shouldBeInstanceOf<InviteRegistrationUiState.SubmitError>()
                err.errorType shouldBe InviteErrorType.InviteInvalid
            }
        }

        test("claimInvite failure with timeout maps to NetworkError") {
            runTest(testDispatcher) {
                val invites = mock<InviteRepository>()
                val serverConfig = mock<ServerConfig>()
                everySuspend { invites.getInviteDetails(SERVER_URL, INVITE_CODE) } returns fakeInviteDetails()
                everySuspend { serverConfig.setServerUrl(any()) } returns Unit
                everySuspend { invites.claimInvite(any(), any(), any()) } throws RuntimeException("connection timed out")
                val vm = newVm(inviteRepository = invites, serverConfig = serverConfig)
                testDispatcher.scheduler.advanceUntilIdle()

                vm.submitRegistration("password123", "password123")
                testDispatcher.scheduler.advanceUntilIdle()

                val err = vm.state.value.shouldBeInstanceOf<InviteRegistrationUiState.SubmitError>()
                err.errorType.shouldBeInstanceOf<InviteErrorType.NetworkError>()
            }
        }

        test("claimInvite 500 maps to ServerError with stable message") {
            runTest(testDispatcher) {
                val invites = mock<InviteRepository>()
                val serverConfig = mock<ServerConfig>()
                everySuspend { invites.getInviteDetails(SERVER_URL, INVITE_CODE) } returns fakeInviteDetails()
                everySuspend { serverConfig.setServerUrl(any()) } returns Unit
                everySuspend { invites.claimInvite(any(), any(), any()) } throws RuntimeException("HTTP 500 internal")
                val vm = newVm(inviteRepository = invites, serverConfig = serverConfig)
                testDispatcher.scheduler.advanceUntilIdle()

                vm.submitRegistration("password123", "password123")
                testDispatcher.scheduler.advanceUntilIdle()

                val err = vm.state.value.shouldBeInstanceOf<InviteRegistrationUiState.SubmitError>()
                val server = err.errorType.shouldBeInstanceOf<InviteErrorType.ServerError>()
                server.detail shouldBe "Server error. Please try again later."
            }
        }

        test("clearError on SubmitError returns to Ready with the same details") {
            runTest(testDispatcher) {
                val invites = mock<InviteRepository>()
                val serverConfig = mock<ServerConfig>()
                everySuspend { invites.getInviteDetails(SERVER_URL, INVITE_CODE) } returns fakeInviteDetails()
                everySuspend { serverConfig.setServerUrl(any()) } returns Unit
                everySuspend { invites.claimInvite(any(), any(), any()) } throws RuntimeException("Boom")
                val vm = newVm(inviteRepository = invites, serverConfig = serverConfig)
                testDispatcher.scheduler.advanceUntilIdle()
                vm.submitRegistration("password123", "password123")
                testDispatcher.scheduler.advanceUntilIdle()
                vm.state.value.shouldBeInstanceOf<InviteRegistrationUiState.SubmitError>()

                vm.clearError()

                val ready = vm.state.value.shouldBeInstanceOf<InviteRegistrationUiState.Ready>()
                ready.details.email shouldBe "alice@example.com"
            }
        }

        test("submission persists serverUrl via ServerConfig before claim") {
            runTest(testDispatcher) {
                val invites = mock<InviteRepository>()
                val serverConfig = mock<ServerConfig>()
                everySuspend { invites.getInviteDetails(SERVER_URL, INVITE_CODE) } returns fakeInviteDetails()
                everySuspend { serverConfig.setServerUrl(any()) } returns Unit
                everySuspend { invites.claimInvite(any(), any(), any()) } returns fakeUser()
                val vm = newVm(inviteRepository = invites, serverConfig = serverConfig)
                testDispatcher.scheduler.advanceUntilIdle()

                vm.submitRegistration("password123", "password123")
                testDispatcher.scheduler.advanceUntilIdle()

                vm.state.value.shouldBeInstanceOf<InviteRegistrationUiState.Submitted>()
                verifySuspend { serverConfig.setServerUrl(ServerUrl(SERVER_URL)) }
            }
        }
    })
