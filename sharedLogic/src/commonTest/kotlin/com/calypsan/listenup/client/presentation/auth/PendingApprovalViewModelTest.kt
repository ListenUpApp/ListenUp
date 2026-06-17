package com.calypsan.listenup.client.presentation.auth

import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.RegistrationStatusStream
import com.calypsan.listenup.client.domain.repository.StreamedRegistrationStatus
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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
 * A controllable [RegistrationStatusStream] fake. [pullStatus] backs the one-shot `fetchStatus`
 * pull (mutate it to simulate the admin's decision); [stream] backs the SSE push path.
 */
private class FakeRegistrationStatusStream(
    var pullStatus: StreamedRegistrationStatus = StreamedRegistrationStatus.Pending,
    private val stream: Flow<StreamedRegistrationStatus> = emptyFlow(),
) : RegistrationStatusStream {
    override fun streamStatus(userId: String): Flow<StreamedRegistrationStatus> = stream

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

        test("an SSE push of Approved still advances the screen") {
            runTest {
                Dispatchers.setMain(StandardTestDispatcher(testScheduler))
                val stream =
                    FakeRegistrationStatusStream(
                        pullStatus = StreamedRegistrationStatus.Pending,
                        stream = flowOf(StreamedRegistrationStatus.Approved),
                    )
                val vm = viewModel(stream)

                advanceUntilIdle()

                vm.state.value shouldBe PendingApprovalUiState.Approved
            }
        }
    })
