package com.calypsan.listenup.client.presentation.auth

import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.RegistrationStatusStream
import com.calypsan.listenup.client.domain.repository.StreamedRegistrationStatus
import dev.mokkery.answering.returns
import dev.mokkery.answering.sequentially
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class PendingApprovalViewModelTest :
    FunSpec({
        afterTest { Dispatchers.resetMain() }

        test("checkStatus re-opens a dropped stream and recovers the approval transition") {
            runTest {
                Dispatchers.setMain(StandardTestDispatcher(testScheduler))
                val authSession: AuthSession = mock()
                val stream: RegistrationStatusStream = mock()
                // First subscription drops immediately (empty); the manual re-check gets a live
                // stream that reports approval.
                every { stream.streamStatus(any()) } sequentially {
                    returns(emptyFlow())
                    returns(flowOf(StreamedRegistrationStatus.Approved))
                }

                val viewModel =
                    PendingApprovalViewModel(
                        authSession = authSession,
                        registrationStatusStream = stream,
                        userId = "user-1",
                        email = "reader@example.com",
                    )
                advanceUntilIdle()
                // The first stream ended without a verdict — still waiting.
                viewModel.state.value shouldBe PendingApprovalUiState.Waiting

                viewModel.checkStatus()
                advanceUntilIdle()

                // Re-subscribing surfaced the approval.
                viewModel.state.value shouldBe PendingApprovalUiState.Approved
            }
        }
    })
