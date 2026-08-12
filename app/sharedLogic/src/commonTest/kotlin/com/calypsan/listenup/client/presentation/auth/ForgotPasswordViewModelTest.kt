package com.calypsan.listenup.client.presentation.auth

import com.calypsan.listenup.api.dto.auth.PasswordResetStatus
import com.calypsan.listenup.api.dto.auth.PasswordResetStatusEvent
import com.calypsan.listenup.api.dto.auth.PasswordResetTicket
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.PasswordResetRepository
import com.calypsan.listenup.core.error.ErrorBus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
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
 * [completeResetDelayMs] lets a test simulate a network call that is still in flight while the
 * background watch discovers something else.
 */
private class FakePasswordResetRepository(
    var resumable: String? = null,
    var requestResetResult: AppResult<PasswordResetTicket> =
        AppResult.Success(PasswordResetTicket(ticketId = "t-1", expiresAt = 1L)),
    var fetchStatusResult: PasswordResetStatusEvent? = null,
    var completeResetResult: AppResult<Unit> = AppResult.Success(Unit),
    var completeResetDelayMs: Long = 0L,
    private val streamFactory: () -> Flow<PasswordResetStatusEvent> = { emptyFlow() },
) : PasswordResetRepository {
    var subscriptionCount = 0
        private set
    var abandonCallCount = 0
        private set

    /** Every address the ViewModel asked for, in order — so a re-ask can be told from a first ask. */
    val requestedEmails = mutableListOf<String>()

    override suspend fun requestReset(email: String): AppResult<PasswordResetTicket> {
        requestedEmails += email
        return requestResetResult
    }

    override fun observeStatus(ticketId: String): Flow<PasswordResetStatusEvent> {
        subscriptionCount++
        return streamFactory()
    }

    override suspend fun fetchStatus(ticketId: String): PasswordResetStatusEvent? = fetchStatusResult

    override suspend fun resumableTicketId(): String? = resumable

    override suspend fun abandonPendingRequest() {
        abandonCallCount++
    }

    override suspend fun completeReset(
        ticketId: String,
        code: String,
        newPassword: String,
    ): AppResult<Unit> {
        if (completeResetDelayMs > 0) delay(completeResetDelayMs)
        return completeResetResult
    }

    override suspend fun resetRootPassword(
        token: String,
        newPassword: String,
    ): AppResult<Unit> = AppResult.Success(Unit)
}

/** Upper bound the test allows for the stream's exhausted-retry subscription count — generous
 * headroom above the ViewModel's actual budget so this test pins "bounded", not the exact constant. */
private const val STREAM_RETRY_BUDGET_UPPER_BOUND = 20

/**
 * Virtual-time window comfortably covering the ViewModel's stream-retry backoff schedule
 * (1s, 2s, 4s, 8s, 16s = 31s total for 5 attempts) so the retry loop fully exhausts within a single
 * bounded [kotlinx.coroutines.test.TestScope.advanceTimeBy] call.
 */
private const val RETRY_BUDGET_EXHAUSTION_MILLIS = 40_000L

@OptIn(ExperimentalCoroutinesApi::class)
class ForgotPasswordViewModelTest :
    FunSpec({
        afterTest { Dispatchers.resetMain() }

        fun viewModel(repository: PasswordResetRepository) = ForgotPasswordViewModel(repository = repository, errorBus = ErrorBus())

        // Every ViewModel constructed with a resumable ticket (or via requestReset) starts a
        // poll/stream watch that does NOT always self-terminate — the whole point of several
        // tests below is to prove it survives repeated ticks or a never-resolving fake. If an
        // assertion throws before an explicit vm.close(), that watch is left dangling: viewModelScope
        // is its own SupervisorJob, not a child of runTest's coroutine, so nothing else cancels it,
        // and runTest's own post-test drain then spins forever trying to reach idle — turning a
        // clean assertion failure into an unreadable hang. `try/finally` guarantees close() runs
        // either way.

        test("requesting a reset moves EnterEmail to AwaitingApproval") {
            runTest {
                Dispatchers.setMain(StandardTestDispatcher(testScheduler))
                val repo = FakePasswordResetRepository(resumable = null)
                val vm = viewModel(repo)
                try {
                    runCurrent()
                    vm.state.value shouldBe ForgotPasswordUiState.EnterEmail

                    // Not advanceUntilIdle(): the default fake never resolves the request further,
                    // so the poll loop this starts would re-schedule itself every 5s forever — the
                    // same "bounded, not idle" caution PendingApprovalViewModelTest documents.
                    vm.requestReset("ada@example.com")
                    runCurrent()

                    val awaiting = vm.state.value.shouldBeInstanceOf<ForgotPasswordUiState.AwaitingApproval>()
                    awaiting.ticketId shouldBe "t-1"
                } finally {
                    vm.close()
                }
            }
        }

        test("asking again after a decline re-opens the request with the address already given") {
            // A decline is usually a misunderstanding, so the requester should not have to walk
            // back through sign-in and retype an address they just entered.
            runTest {
                Dispatchers.setMain(StandardTestDispatcher(testScheduler))
                val repo = FakePasswordResetRepository(resumable = null)
                val vm = viewModel(repo)
                try {
                    runCurrent()
                    vm.requestReset("ada@example.com")
                    runCurrent()

                    vm.retryRequest()
                    runCurrent()

                    repo.requestedEmails shouldBe listOf("ada@example.com", "ada@example.com")
                    vm.state.value.shouldBeInstanceOf<ForgotPasswordUiState.AwaitingApproval>()
                } finally {
                    vm.close()
                }
            }
        }

        test("asking again with nothing remembered falls back to the email step") {
            // The ViewModel is screen-scoped, so a process death takes the address with it.
            // Asking for it again beats failing silently.
            runTest {
                Dispatchers.setMain(StandardTestDispatcher(testScheduler))
                val repo = FakePasswordResetRepository(resumable = null)
                val vm = viewModel(repo)
                try {
                    runCurrent()

                    vm.retryRequest()
                    runCurrent()

                    repo.requestedEmails.shouldBeEmpty()
                    vm.state.value shouldBe ForgotPasswordUiState.EnterEmail
                } finally {
                    vm.close()
                }
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
                try {
                    advanceUntilIdle()

                    val enterCode = vm.state.value.shouldBeInstanceOf<ForgotPasswordUiState.EnterCode>()
                    enterCode.ticketId shouldBe "t-1"
                } finally {
                    vm.close()
                }
            }
        }

        test("a denied status moves to Denied, stops watching, and abandons the persisted request") {
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
                try {
                    advanceUntilIdle()

                    vm.state.value shouldBe ForgotPasswordUiState.Denied
                    repo.subscriptionCount shouldBe 1
                    repo.abandonCallCount shouldBe 1

                    // No further ticks resurrect the watch or move the state.
                    advanceTimeBy(60_000L)
                    advanceUntilIdle()

                    vm.state.value shouldBe ForgotPasswordUiState.Denied
                    repo.subscriptionCount shouldBe 1
                } finally {
                    vm.close()
                }
            }
        }

        test("a consumed status moves to Complete") {
            runTest {
                Dispatchers.setMain(StandardTestDispatcher(testScheduler))
                val repo =
                    FakePasswordResetRepository(
                        resumable = "t-1",
                        streamFactory = {
                            flowOf(PasswordResetStatusEvent(status = PasswordResetStatus.CONSUMED, expiresAt = 1L))
                        },
                    )
                val vm = viewModel(repo)
                try {
                    advanceUntilIdle()

                    vm.state.value shouldBe ForgotPasswordUiState.Complete
                    // CONSUMED only ever follows this device's own successful completeReset, whose
                    // own success path already clears both keys — no extra abandon call needed.
                    repo.abandonCallCount shouldBe 0
                } finally {
                    vm.close()
                }
            }
        }

        test("an expired status moves to Error and abandons the persisted request") {
            runTest {
                Dispatchers.setMain(StandardTestDispatcher(testScheduler))
                val repo =
                    FakePasswordResetRepository(
                        resumable = "t-1",
                        streamFactory = {
                            flowOf(PasswordResetStatusEvent(status = PasswordResetStatus.EXPIRED, expiresAt = 1L))
                        },
                    )
                val vm = viewModel(repo)
                try {
                    advanceUntilIdle()

                    vm.state.value.shouldBeInstanceOf<ForgotPasswordUiState.Error>()
                    repo.abandonCallCount shouldBe 1
                } finally {
                    vm.close()
                }
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
                try {
                    advanceUntilIdle()

                    vm.state.value.shouldBeInstanceOf<ForgotPasswordUiState.EnterCode>()
                    repo.subscriptionCount shouldBe 1

                    // Several minutes of virtual time: if completion were mistaken for a dropped
                    // connection, subscriptionCount would climb without bound.
                    advanceTimeBy(5 * 60_000L)
                    advanceUntilIdle()

                    repo.subscriptionCount shouldBe 1
                } finally {
                    vm.close()
                }
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
                try {
                    advanceUntilIdle()
                    vm.state.value.shouldBeInstanceOf<ForgotPasswordUiState.EnterCode>()

                    vm.completeReset("WRNG-CODE", "correct horse battery")
                    advanceUntilIdle()

                    val enterCode = vm.state.value.shouldBeInstanceOf<ForgotPasswordUiState.EnterCode>()
                    enterCode.attemptsRemaining shouldBe 2
                } finally {
                    vm.close()
                }
            }
        }

        test("a repeated APPROVED tick does not erase wrong-code feedback") {
            runTest {
                Dispatchers.setMain(StandardTestDispatcher(testScheduler))
                // The server's watch is not distinct-until-changed — it re-emits APPROVED every
                // poll interval for as long as the status stays APPROVED. A fake with a genuinely
                // finite flowOf(...) can't reproduce that; this repeats forever, like the real one.
                val repo =
                    FakePasswordResetRepository(
                        resumable = "t-1",
                        streamFactory = {
                            flow {
                                while (true) {
                                    emit(PasswordResetStatusEvent(status = PasswordResetStatus.APPROVED, expiresAt = 1L))
                                    delay(5_000L)
                                }
                            }
                        },
                        completeResetResult = AppResult.Failure(AuthError.ResetCodeIncorrect(attemptsRemaining = 2)),
                    )
                val vm = viewModel(repo)
                try {
                    runCurrent()
                    vm.state.value.shouldBeInstanceOf<ForgotPasswordUiState.EnterCode>()

                    vm.completeReset("WRNG-CODE", "correct horse battery")
                    runCurrent()

                    val afterWrongCode = vm.state.value.shouldBeInstanceOf<ForgotPasswordUiState.EnterCode>()
                    afterWrongCode.attemptsRemaining shouldBe 2

                    // Let several more repeated APPROVED ticks land — none of them may erase the
                    // feedback just captured above. Not advanceUntilIdle(): the stream never completes.
                    advanceTimeBy(3 * 5_000L)
                    runCurrent()

                    val stillFeedback = vm.state.value.shouldBeInstanceOf<ForgotPasswordUiState.EnterCode>()
                    stillFeedback.attemptsRemaining shouldBe 2
                } finally {
                    vm.close()
                }
            }
        }

        test("a terminal status discovered while completeReset is in flight is not clobbered by stale feedback") {
            runTest {
                Dispatchers.setMain(StandardTestDispatcher(testScheduler))
                val repo =
                    FakePasswordResetRepository(
                        resumable = "t-1",
                        streamFactory = {
                            flow {
                                emit(PasswordResetStatusEvent(status = PasswordResetStatus.APPROVED, expiresAt = 1L))
                                delay(2_000L)
                                emit(PasswordResetStatusEvent(status = PasswordResetStatus.DENIED, expiresAt = 1L))
                            }
                        },
                        completeResetResult = AppResult.Failure(AuthError.ResetCodeIncorrect(attemptsRemaining = 2)),
                        completeResetDelayMs = 5_000L,
                    )
                val vm = viewModel(repo)
                try {
                    runCurrent()
                    vm.state.value.shouldBeInstanceOf<ForgotPasswordUiState.EnterCode>()

                    // Kick off completeReset — its response won't resolve for 5s.
                    vm.completeReset("WRNG-CODE", "correct horse battery")
                    runCurrent()

                    // Before that network call resolves, the background watch discovers DENIED.
                    advanceTimeBy(2_000L)
                    runCurrent()
                    vm.state.value shouldBe ForgotPasswordUiState.Denied

                    // The stale completeReset response now lands — it must NOT resurrect EnterCode.
                    advanceTimeBy(3_000L)
                    runCurrent()
                    vm.state.value shouldBe ForgotPasswordUiState.Denied
                } finally {
                    vm.close()
                }
            }
        }

        test("checkStatus pulls status immediately, even without the stream") {
            runTest {
                Dispatchers.setMain(StandardTestDispatcher(testScheduler))
                val repo = FakePasswordResetRepository(resumable = "t-1")
                val vm = viewModel(repo)
                try {
                    runCurrent()
                    vm.state.value.shouldBeInstanceOf<ForgotPasswordUiState.AwaitingApproval>()

                    repo.fetchStatusResult = PasswordResetStatusEvent(status = PasswordResetStatus.APPROVED, expiresAt = 1L)
                    vm.checkStatus()
                    runCurrent()

                    vm.state.value.shouldBeInstanceOf<ForgotPasswordUiState.EnterCode>()
                } finally {
                    vm.close()
                }
            }
        }

        test("the poll fallback alone reaches EnterCode when the stream never delivers") {
            runTest {
                Dispatchers.setMain(StandardTestDispatcher(testScheduler))
                // Default streamFactory (emptyFlow()) never delivers — exactly the case the poll
                // fallback exists for.
                val repo = FakePasswordResetRepository(resumable = "t-1")
                val vm = viewModel(repo)
                try {
                    runCurrent()
                    vm.state.value.shouldBeInstanceOf<ForgotPasswordUiState.AwaitingApproval>()

                    repo.fetchStatusResult = PasswordResetStatusEvent(status = PasswordResetStatus.APPROVED, expiresAt = 1L)
                    advanceTimeBy(6_000L) // next poll tick fires
                    runCurrent()

                    vm.state.value.shouldBeInstanceOf<ForgotPasswordUiState.EnterCode>()
                } finally {
                    vm.close()
                }
            }
        }

        test("stream error falls back to polling and does not tight-loop") {
            runTest {
                Dispatchers.setMain(StandardTestDispatcher(testScheduler))
                val repo =
                    FakePasswordResetRepository(
                        resumable = "t-1",
                        streamFactory = { flow { throw IllegalStateException("stream transport failed") } },
                    )
                val vm = viewModel(repo)
                try {
                    // The retry-with-backoff loop exhausts its bounded budget under virtual time. A
                    // BOUNDED advance, not advanceUntilIdle() — the poll loop re-schedules itself
                    // every 5s for as long as the status stays AwaitingApproval, so advanceUntilIdle()
                    // would spin forever trying to reach a "nothing left scheduled" state that never
                    // arrives.
                    advanceTimeBy(RETRY_BUDGET_EXHAUSTION_MILLIS)
                    runCurrent()
                    vm.state.value.shouldBeInstanceOf<ForgotPasswordUiState.AwaitingApproval>()
                    val exhaustedCount = repo.subscriptionCount
                    exhaustedCount shouldBeLessThanOrEqual STREAM_RETRY_BUDGET_UPPER_BOUND

                    // The poll fallback (untouched by the stream's failures) still drives the screen.
                    repo.fetchStatusResult = PasswordResetStatusEvent(status = PasswordResetStatus.APPROVED, expiresAt = 1L)
                    advanceTimeBy(6_000L)
                    runCurrent()

                    vm.state.value.shouldBeInstanceOf<ForgotPasswordUiState.EnterCode>()
                    // No further stream subscriptions were attempted once the retry budget was spent.
                    repo.subscriptionCount shouldBe exhaustedCount
                } finally {
                    vm.close()
                }
            }
        }

        test("a request left in flight by a previous run is resumed on construction") {
            runTest {
                Dispatchers.setMain(StandardTestDispatcher(testScheduler))
                val repo = FakePasswordResetRepository(resumable = "t-1")
                val vm = viewModel(repo)
                try {
                    runCurrent()

                    val awaiting = vm.state.value.shouldBeInstanceOf<ForgotPasswordUiState.AwaitingApproval>()
                    awaiting.ticketId shouldBe "t-1"
                } finally {
                    // Same dangling-poll-loop hazard as the first test above.
                    vm.close()
                }
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
