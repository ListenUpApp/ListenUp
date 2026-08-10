package com.calypsan.listenup.client.presentation.auth

import com.calypsan.listenup.api.dto.auth.PasswordResetStatus
import com.calypsan.listenup.api.dto.auth.PasswordResetStatusEvent
import com.calypsan.listenup.api.dto.auth.PasswordResetTicket
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.PasswordResetRepository
import com.calypsan.listenup.core.error.ErrorBus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * A controllable [PasswordResetRepository] fake. [streamFactory] builds a fresh flow for the push
 * path on every [observeStatus] subscription — [subscriptionCount] records how many times it was
 * actually called, so a test can assert the watcher's retry loop stays bounded.
 */
private class FakePasswordResetRepository(
    var resumable: String? = null,
    var requestResetResult: AppResult<PasswordResetTicket> =
        AppResult.Success(PasswordResetTicket(ticketId = "t-1", expiresAt = 1L)),
    var fetchStatusResult: PasswordResetStatusEvent? = null,
    var completeResetResult: AppResult<Unit> = AppResult.Success(Unit),
    private val streamFactory: () -> Flow<PasswordResetStatusEvent> = { emptyFlow() },
) : PasswordResetRepository {
    var subscriptionCount = 0
        private set

    override suspend fun requestReset(email: String): AppResult<PasswordResetTicket> = requestResetResult

    override fun observeStatus(ticketId: String): Flow<PasswordResetStatusEvent> {
        subscriptionCount++
        return streamFactory()
    }

    override suspend fun fetchStatus(ticketId: String): PasswordResetStatusEvent? = fetchStatusResult

    override suspend fun resumableTicketId(): String? = resumable

    override suspend fun completeReset(
        ticketId: String,
        code: String,
        newPassword: String,
    ): AppResult<Unit> = completeResetResult

    override suspend fun resetRootPassword(
        token: String,
        newPassword: String,
    ): AppResult<Unit> = AppResult.Success(Unit)
}

@OptIn(ExperimentalCoroutinesApi::class)
class ForgotPasswordViewModelTest :
    FunSpec({
        afterTest { Dispatchers.resetMain() }

        fun viewModel(repository: PasswordResetRepository) = ForgotPasswordViewModel(repository = repository, errorBus = ErrorBus())

        test("requesting a reset moves EnterEmail to AwaitingApproval") {
            runTest {
                Dispatchers.setMain(StandardTestDispatcher(testScheduler))
                val repo = FakePasswordResetRepository(resumable = null)
                val vm = viewModel(repo)
                runCurrent()
                vm.state.value shouldBe ForgotPasswordUiState.EnterEmail

                // Not advanceUntilIdle(): the default fake never resolves the request further, so
                // the poll loop this starts would re-schedule itself every 5s forever — the same
                // "bounded, not idle" caution PendingApprovalViewModelTest documents.
                vm.requestReset("ada@example.com")
                runCurrent()

                val awaiting = vm.state.value.shouldBeInstanceOf<ForgotPasswordUiState.AwaitingApproval>()
                awaiting.ticketId shouldBe "t-1"

                // The poll loop is still perpetually re-scheduling itself (never resolved to a
                // terminal status) — close() so runTest's own post-test drain doesn't spin
                // forever trying to reach idle.
                vm.close()
            }
        }

        test("an approved status moves to EnterCode") {
            runTest {
                Dispatchers.setMain(StandardTestDispatcher(testScheduler))
                val repo =
                    FakePasswordResetRepository(
                        resumable = "t-1",
                        streamFactory = {
                            flowOf(PasswordResetStatusEvent(status = PasswordResetStatus.APPROVED, expiresAt = 1L))
                        },
                    )
                val vm = viewModel(repo)
                advanceUntilIdle()

                val enterCode = vm.state.value.shouldBeInstanceOf<ForgotPasswordUiState.EnterCode>()
                enterCode.ticketId shouldBe "t-1"

                vm.close()
            }
        }

        test("a denied status moves to Denied and stops watching") {
            runTest {
                Dispatchers.setMain(StandardTestDispatcher(testScheduler))
                val repo =
                    FakePasswordResetRepository(
                        resumable = "t-1",
                        streamFactory = {
                            flowOf(PasswordResetStatusEvent(status = PasswordResetStatus.DENIED, expiresAt = 1L))
                        },
                    )
                val vm = viewModel(repo)
                advanceUntilIdle()

                vm.state.value shouldBe ForgotPasswordUiState.Denied
                repo.subscriptionCount shouldBe 1

                // No further ticks resurrect the watch or move the state.
                advanceTimeBy(60_000L)
                advanceUntilIdle()

                vm.state.value shouldBe ForgotPasswordUiState.Denied
                repo.subscriptionCount shouldBe 1

                vm.close()
            }
        }

        test("the stream completing does NOT trigger a reconnect — completion is the signal") {
            runTest {
                Dispatchers.setMain(StandardTestDispatcher(testScheduler))
                // PENDING then APPROVED, then the flow completes — exactly what the real terminal-
                // completing RPC watch does while the status stays non-terminal.
                val repo =
                    FakePasswordResetRepository(
                        resumable = "t-1",
                        streamFactory = {
                            flowOf(
                                PasswordResetStatusEvent(status = PasswordResetStatus.PENDING, expiresAt = 1L),
                                PasswordResetStatusEvent(status = PasswordResetStatus.APPROVED, expiresAt = 1L),
                            )
                        },
                    )
                val vm = viewModel(repo)
                advanceUntilIdle()

                vm.state.value.shouldBeInstanceOf<ForgotPasswordUiState.EnterCode>()
                repo.subscriptionCount shouldBe 1

                // Several minutes of virtual time: if completion were mistaken for a dropped
                // connection, subscriptionCount would climb without bound.
                advanceTimeBy(5 * 60_000L)
                advanceUntilIdle()

                repo.subscriptionCount shouldBe 1

                vm.close()
            }
        }

        test("a wrong code surfaces the remaining attempts") {
            runTest {
                Dispatchers.setMain(StandardTestDispatcher(testScheduler))
                val repo =
                    FakePasswordResetRepository(
                        resumable = "t-1",
                        streamFactory = {
                            flowOf(PasswordResetStatusEvent(status = PasswordResetStatus.APPROVED, expiresAt = 1L))
                        },
                        completeResetResult = AppResult.Failure(AuthError.ResetCodeIncorrect(attemptsRemaining = 2)),
                    )
                val vm = viewModel(repo)
                advanceUntilIdle()
                vm.state.value.shouldBeInstanceOf<ForgotPasswordUiState.EnterCode>()

                vm.completeReset("WRNG-CODE", "correct horse battery")
                advanceUntilIdle()

                val enterCode = vm.state.value.shouldBeInstanceOf<ForgotPasswordUiState.EnterCode>()
                enterCode.attemptsRemaining shouldBe 2

                vm.close()
            }
        }

        test("a request left in flight by a previous run is resumed on construction") {
            runTest {
                Dispatchers.setMain(StandardTestDispatcher(testScheduler))
                val repo = FakePasswordResetRepository(resumable = "t-1")
                val vm = viewModel(repo)
                runCurrent()

                val awaiting = vm.state.value.shouldBeInstanceOf<ForgotPasswordUiState.AwaitingApproval>()
                awaiting.ticketId shouldBe "t-1"

                // Same dangling-poll-loop hazard as the first test above.
                vm.close()
            }
        }

        test("with nothing in flight the flow starts at EnterEmail") {
            runTest {
                Dispatchers.setMain(StandardTestDispatcher(testScheduler))
                val repo = FakePasswordResetRepository(resumable = null)
                val vm = viewModel(repo)
                runCurrent()

                vm.state.value shouldBe ForgotPasswordUiState.EnterEmail
            }
        }
    })
