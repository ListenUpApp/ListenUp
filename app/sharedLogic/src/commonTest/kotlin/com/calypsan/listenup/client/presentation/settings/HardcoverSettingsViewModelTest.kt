package com.calypsan.listenup.client.presentation.settings

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import app.cash.turbine.turbineScope
import com.calypsan.listenup.api.dto.hardcover.HardcoverBrokenReason
import com.calypsan.listenup.api.dto.hardcover.HardcoverConnection
import com.calypsan.listenup.api.dto.hardcover.HardcoverLinkFailure
import com.calypsan.listenup.api.error.HardcoverError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.presentation.settings.FakeHardcoverRepository.Companion.SAMPLE_PROMPT
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

private const val SINCE = 1_700_000_000_000L

/** Skips the pre-answer [HardcoverSettingsUiState.Loading] and returns the first real state. */
private suspend fun ReceiveTurbine<HardcoverSettingsUiState>.awaitSettled(): HardcoverSettingsUiState {
    var item = awaitItem()
    while (item is HardcoverSettingsUiState.Loading) item = awaitItem()
    return item
}

/**
 * Tests for [HardcoverSettingsViewModel]: the server's connection stream mapped to screen state,
 * the in-flight flags, and the one-shot events Connect / Disconnect / Open Hardcover produce.
 *
 * Uses [FakeHardcoverRepository] (in-memory stream, scripted results, gates for in-flight calls).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HardcoverSettingsViewModelTest :
    FunSpec({
        val testDispatcher = StandardTestDispatcher()

        beforeTest { Dispatchers.setMain(testDispatcher) }
        afterTest { Dispatchers.resetMain() }

        // ========== State mapping ==========

        test("the screen shows Loading until the server's first answer") {
            runTest {
                val repo = FakeHardcoverRepository()
                val vm = HardcoverSettingsViewModel(repo)

                vm.uiState.test {
                    awaitItem() shouldBe HardcoverSettingsUiState.Loading
                    repo.connection.value = HardcoverConnection.NotOffered
                    awaitItem() shouldBe HardcoverSettingsUiState.NotOffered
                }
            }
        }

        test("every connection state maps to its screen state") {
            runTest {
                val repo = FakeHardcoverRepository(HardcoverConnection.NotConnected())
                val vm = HardcoverSettingsViewModel(repo)

                vm.uiState.test {
                    awaitSettled() shouldBe HardcoverSettingsUiState.NotConnected(lastFailure = null, isStarting = false)

                    repo.connection.value = HardcoverConnection.NotConnected(HardcoverLinkFailure.EXPIRED)
                    awaitItem() shouldBe
                        HardcoverSettingsUiState.NotConnected(
                            lastFailure = HardcoverLinkFailure.EXPIRED,
                            isStarting = false,
                        )

                    repo.connection.value = HardcoverConnection.Linking(SAMPLE_PROMPT)
                    awaitItem() shouldBe
                        HardcoverSettingsUiState.Linking(
                            userCode = SAMPLE_PROMPT.userCode,
                            verificationUri = SAMPLE_PROMPT.verificationUri,
                            verificationUriComplete = SAMPLE_PROMPT.verificationUriComplete,
                            expiresAt = SAMPLE_PROMPT.expiresAt,
                        )

                    repo.connection.value = HardcoverConnection.Connected("reader", SINCE)
                    awaitItem() shouldBe
                        HardcoverSettingsUiState.Connected(username = "reader", since = SINCE, isDisconnecting = false)

                    repo.connection.value = HardcoverConnection.Broken(HardcoverBrokenReason.REVOKED, "reader")
                    awaitItem() shouldBe
                        HardcoverSettingsUiState.Broken(
                            reason = HardcoverBrokenReason.REVOKED,
                            username = "reader",
                            isStarting = false,
                        )

                    repo.connection.value = HardcoverConnection.Broken(HardcoverBrokenReason.MISSING_SCOPE, null)
                    awaitItem() shouldBe
                        HardcoverSettingsUiState.Broken(
                            reason = HardcoverBrokenReason.MISSING_SCOPE,
                            username = null,
                            isStarting = false,
                        )

                    repo.connection.value = HardcoverConnection.NotOffered
                    awaitItem() shouldBe HardcoverSettingsUiState.NotOffered
                }
            }
        }

        // ========== Connect ==========

        test("connect opens the pre-filled approval page") {
            runTest {
                val repo = FakeHardcoverRepository(HardcoverConnection.NotConnected())
                val vm = HardcoverSettingsViewModel(repo)

                vm.events.test {
                    vm.connect()
                    awaitItem() shouldBe
                        HardcoverSettingsEvent.OpenVerificationPage(SAMPLE_PROMPT.verificationUriComplete)
                }
            }
        }

        test("a refused connect shows the error and opens nothing") {
            runTest {
                val error = HardcoverError.Unavailable()
                val repo =
                    FakeHardcoverRepository(HardcoverConnection.NotConnected()).apply {
                        startLinkResult = AppResult.Failure(error)
                    }
                val vm = HardcoverSettingsViewModel(repo)

                vm.events.test {
                    vm.connect()
                    awaitItem() shouldBe HardcoverSettingsEvent.ShowError(error)
                    advanceUntilIdle()
                    expectNoEvents()
                }
            }
        }

        test("isStarting is true while connect is in flight, and a second tap starts nothing") {
            runTest {
                val gate = CompletableDeferred<Unit>()
                val repo =
                    FakeHardcoverRepository(HardcoverConnection.NotConnected()).apply { startLinkGate = gate }
                val vm = HardcoverSettingsViewModel(repo)

                vm.uiState.test {
                    awaitSettled() shouldBe HardcoverSettingsUiState.NotConnected(lastFailure = null, isStarting = false)

                    vm.connect()
                    awaitItem() shouldBe HardcoverSettingsUiState.NotConnected(lastFailure = null, isStarting = true)

                    vm.connect()
                    advanceUntilIdle()
                    repo.startLinkCalls shouldBe 1

                    gate.complete(Unit)
                    awaitItem() shouldBe HardcoverSettingsUiState.NotConnected(lastFailure = null, isStarting = false)
                }
                repo.startLinkCalls shouldBe 1
            }
        }

        test("reconnect from Broken shows isStarting while in flight") {
            runTest {
                val gate = CompletableDeferred<Unit>()
                val repo =
                    FakeHardcoverRepository(HardcoverConnection.Broken(HardcoverBrokenReason.CANNOT_DECRYPT, "reader")).apply {
                        startLinkGate = gate
                    }
                val vm = HardcoverSettingsViewModel(repo)

                vm.uiState.test {
                    awaitSettled() shouldBe
                        HardcoverSettingsUiState.Broken(HardcoverBrokenReason.CANNOT_DECRYPT, "reader", isStarting = false)

                    vm.connect()
                    awaitItem() shouldBe
                        HardcoverSettingsUiState.Broken(HardcoverBrokenReason.CANNOT_DECRYPT, "reader", isStarting = true)

                    gate.complete(Unit)
                    awaitItem() shouldBe
                        HardcoverSettingsUiState.Broken(HardcoverBrokenReason.CANNOT_DECRYPT, "reader", isStarting = false)
                }
                repo.startLinkCalls shouldBe 1
            }
        }

        // ========== Disconnect ==========

        test("disconnect from Connected shows isDisconnecting while in flight, once however often it is tapped") {
            runTest {
                val gate = CompletableDeferred<Unit>()
                val repo =
                    FakeHardcoverRepository(HardcoverConnection.Connected("reader", SINCE)).apply {
                        disconnectGate = gate
                    }
                val vm = HardcoverSettingsViewModel(repo)

                vm.uiState.test {
                    awaitSettled() shouldBe HardcoverSettingsUiState.Connected("reader", SINCE, isDisconnecting = false)

                    vm.disconnect()
                    awaitItem() shouldBe HardcoverSettingsUiState.Connected("reader", SINCE, isDisconnecting = true)

                    vm.disconnect()
                    advanceUntilIdle()
                    repo.disconnectCalls shouldBe 1

                    gate.complete(Unit)
                    awaitItem() shouldBe HardcoverSettingsUiState.Connected("reader", SINCE, isDisconnecting = false)

                    // The server's stream, not the VM, moves the screen on.
                    repo.connection.value = HardcoverConnection.NotConnected()
                    awaitItem() shouldBe HardcoverSettingsUiState.NotConnected(lastFailure = null, isStarting = false)
                }
                repo.disconnectCalls shouldBe 1
            }
        }

        test("disconnect from Linking cancels the pending sign-in") {
            runTest {
                val repo = FakeHardcoverRepository(HardcoverConnection.Linking(SAMPLE_PROMPT))
                val vm = HardcoverSettingsViewModel(repo)

                vm.events.test {
                    vm.disconnect()
                    advanceUntilIdle()
                    expectNoEvents()
                }
                repo.disconnectCalls shouldBe 1
            }
        }

        test("a refused disconnect shows the error") {
            runTest {
                val error = HardcoverError.Unavailable()
                val repo =
                    FakeHardcoverRepository(HardcoverConnection.Connected("reader", SINCE)).apply {
                        disconnectResult = AppResult.Failure(error)
                    }
                val vm = HardcoverSettingsViewModel(repo)

                vm.events.test {
                    vm.disconnect()
                    awaitItem() shouldBe HardcoverSettingsEvent.ShowError(error)
                }
            }
        }

        // ========== Open Hardcover ==========

        test("openVerificationPage re-opens the current prompt's pre-filled page while Linking") {
            runTest {
                val repo = FakeHardcoverRepository(HardcoverConnection.Linking(SAMPLE_PROMPT))
                val vm = HardcoverSettingsViewModel(repo)

                turbineScope {
                    val states = vm.uiState.testIn(backgroundScope)
                    val events = vm.events.testIn(backgroundScope)
                    states.awaitSettled()

                    vm.openVerificationPage()

                    events.awaitItem() shouldBe
                        HardcoverSettingsEvent.OpenVerificationPage(SAMPLE_PROMPT.verificationUriComplete)
                    states.cancelAndIgnoreRemainingEvents()
                    events.cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("openVerificationPage does nothing outside Linking") {
            runTest {
                val repo = FakeHardcoverRepository(HardcoverConnection.Connected("reader", SINCE))
                val vm = HardcoverSettingsViewModel(repo)

                turbineScope {
                    val states = vm.uiState.testIn(backgroundScope)
                    val events = vm.events.testIn(backgroundScope)
                    states.awaitSettled()

                    vm.openVerificationPage()
                    advanceUntilIdle()

                    events.expectNoEvents()
                    states.cancelAndIgnoreRemainingEvents()
                    events.cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })
