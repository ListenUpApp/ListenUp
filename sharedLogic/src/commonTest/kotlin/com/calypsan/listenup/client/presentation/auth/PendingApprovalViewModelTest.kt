package com.calypsan.listenup.client.presentation.auth

import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.RegistrationStatusStream
import com.calypsan.listenup.client.domain.repository.StreamedRegistrationStatus
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * A controllable [RegistrationStatusStream] fake. [pullStatus] backs the one-shot `fetchStatus`
 * pull (mutate it to simulate the admin's decision); [streamFactory] builds a fresh flow for the
 * push path on every subscription — [subscriptionCount] records how many times [streamStatus] was
 * actually called, so a test can assert the watcher's retry loop stays bounded.
 */
private class FakeRegistrationStatusStream(
    var pullStatus: StreamedRegistrationStatus = StreamedRegistrationStatus.Pending,
    private val streamFactory: () -> Flow<StreamedRegistrationStatus> = { emptyFlow() },
) : RegistrationStatusStream {
    var subscriptionCount = 0
        private set

    override fun streamStatus(userId: String): Flow<StreamedRegistrationStatus> {
        subscriptionCount++
        return streamFactory()
    }

    override suspend fun fetchStatus(userId: String): StreamedRegistrationStatus = pullStatus
}

@OptIn(ExperimentalCoroutinesApi::class)
class PendingApprovalViewModelTest :
    FunSpec({
        afterTest { Dispatchers.resetMain() }

        fun viewModel(stream: RegistrationStatusStream) =
            PendingApprovalViewModel(
                authSession = mock<AuthSession>(),
                registrationStatusStream = stream,
                userId = "user-1",
                email = "reader@example.com",
            )

        test("the automatic poll advances to Approved when the registration is approved (no SSE)") {
            runTest {
                Dispatchers.setMain(StandardTestDispatcher(testScheduler))
                // SSE never delivers (empty) — exactly the iOS-Darwin case the pull fallback exists for.
                val stream = FakeRegistrationStatusStream(pullStatus = StreamedRegistrationStatus.Pending)
                val vm = viewModel(stream)

                runCurrent() // immediate on-entry check resolves pending
                vm.state.value shouldBe PendingApprovalUiState.Waiting

                stream.pullStatus = StreamedRegistrationStatus.Approved
                advanceTimeBy(6_000) // next poll tick fires
                advanceUntilIdle()

                vm.state.value shouldBe PendingApprovalUiState.Approved
            }
        }

        test("checkStatus pulls the approval immediately, even without the SSE stream") {
            runTest {
                Dispatchers.setMain(StandardTestDispatcher(testScheduler))
                val stream = FakeRegistrationStatusStream(pullStatus = StreamedRegistrationStatus.Pending)
                val vm = viewModel(stream)
                runCurrent()
                vm.state.value shouldBe PendingApprovalUiState.Waiting

                stream.pullStatus = StreamedRegistrationStatus.Approved
                vm.checkStatus()
                advanceUntilIdle()

                vm.state.value shouldBe PendingApprovalUiState.Approved
            }
        }

        test("a stream push of Approved still advances the screen") {
            runTest {
                Dispatchers.setMain(StandardTestDispatcher(testScheduler))
                val stream =
                    FakeRegistrationStatusStream(
                        pullStatus = StreamedRegistrationStatus.Pending,
                        streamFactory = { flowOf(StreamedRegistrationStatus.Approved) },
                    )
                val vm = viewModel(stream)

                advanceUntilIdle()

                vm.state.value shouldBe PendingApprovalUiState.Approved
            }
        }

        test("watcher subscribes at most a bounded number of times and STOPS after a terminal status") {
            runTest {
                Dispatchers.setMain(StandardTestDispatcher(testScheduler))
                // The real RPC watch emits Approved then COMPLETES — a completing flow, not an
                // ever-reconnecting one. If the watcher mistook that completion for a dropped
                // connection and kept resubscribing, this count would climb without bound.
                val stream =
                    FakeRegistrationStatusStream(
                        pullStatus = StreamedRegistrationStatus.Pending,
                        streamFactory = { flowOf(StreamedRegistrationStatus.Approved) },
                    )
                val vm = viewModel(stream)

                advanceUntilIdle()
                vm.state.value shouldBe PendingApprovalUiState.Approved
                stream.subscriptionCount shouldBe 1

                advanceTimeBy(5 * 60_000L) // several minutes of virtual time
                advanceUntilIdle()

                stream.subscriptionCount shouldBe 1
            }
        }

        test("stream error falls back to polling and does not tight-loop") {
            runTest {
                Dispatchers.setMain(StandardTestDispatcher(testScheduler))
                val stream =
                    FakeRegistrationStatusStream(
                        pullStatus = StreamedRegistrationStatus.Pending,
                        streamFactory = { flow { throw IllegalStateException("stream transport failed") } },
                    )
                val vm = viewModel(stream)

                // The retry-with-backoff loop exhausts its bounded budget under virtual time.
                advanceUntilIdle()
                vm.state.value shouldBe PendingApprovalUiState.Waiting
                val exhaustedCount = stream.subscriptionCount
                exhaustedCount shouldBeLessThanOrEqual STREAM_RETRY_BUDGET_UPPER_BOUND

                // The poll fallback (untouched by the stream's failures) still drives the screen.
                stream.pullStatus = StreamedRegistrationStatus.Approved
                advanceTimeBy(6_000)
                advanceUntilIdle()

                vm.state.value shouldBe PendingApprovalUiState.Approved
                // No further stream subscriptions were attempted once the retry budget was spent.
                stream.subscriptionCount shouldBe exhaustedCount
            }
        }

        // The behaviour behind PendingApprovalViewModelWrapper.deinit's `viewModel.close()` call
        // (iOS has no ViewModelStore, so onCleared() never fires on its own — see the class KDoc).
        test("close() cancels the stream/poll jobs — no further state updates land after close") {
            runTest {
                Dispatchers.setMain(StandardTestDispatcher(testScheduler))
                val stream = FakeRegistrationStatusStream(pullStatus = StreamedRegistrationStatus.Pending)
                val vm = viewModel(stream)
                runCurrent()
                vm.state.value shouldBe PendingApprovalUiState.Waiting

                vm.close()

                // If the poll/stream loops survived close(), this later approval would still land.
                stream.pullStatus = StreamedRegistrationStatus.Approved
                advanceTimeBy(60_000)
                advanceUntilIdle()

                vm.state.value shouldBe PendingApprovalUiState.Waiting
            }
        }

        test("close() is idempotent") {
            runTest {
                Dispatchers.setMain(StandardTestDispatcher(testScheduler))
                val vm = viewModel(FakeRegistrationStatusStream())
                runCurrent()

                vm.close()
                vm.close() // must not throw
            }
        }
    })

/** Upper bound the test allows for the stream's exhausted-retry subscription count — generous
 * headroom above the ViewModel's actual budget so this test pins "bounded", not the exact constant. */
private const val STREAM_RETRY_BUDGET_UPPER_BOUND = 20
