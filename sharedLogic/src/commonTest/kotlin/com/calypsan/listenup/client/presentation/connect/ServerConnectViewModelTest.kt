package com.calypsan.listenup.client.presentation.connect

import com.calypsan.listenup.api.dto.ServerInfo
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.error.ServerConnectError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.client.checkIs
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.domain.repository.VerifiedServer
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Tests for ServerConnectViewModel.
 *
 * Covers local validation and state-machine behaviour. Network verification
 * paths are exercised indirectly through the validation fallthrough; the
 * real InstanceRepository is not injected here because its construction
 * involves HttpClient internals that would require integration testing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerConnectViewModelTest :
    FunSpec({
        val testDispatcher = StandardTestDispatcher()

        class TestFixture {
            val serverConfig: ServerConfig = mock()
            val instanceRepository: InstanceRepository = mock()

            fun build(appScope: CoroutineScope): ServerConnectViewModel =
                ServerConnectViewModel(
                    serverConfig = serverConfig,
                    instanceRepository = instanceRepository,
                    appScope = appScope,
                )
        }

        fun createFixture(): TestFixture = TestFixture()

        beforeTest {
            Dispatchers.setMain(testDispatcher)
        }

        afterTest {
            Dispatchers.resetMain()
        }

        // ========== Initial State ==========

        test("initial state is Idle") {
            runTest {
                val viewModel = createFixture().build(CoroutineScope(testDispatcher))

                viewModel.state.value shouldBe ServerConnectUiState.Idle
            }
        }

        // ========== URL Validation ==========

        test("submitUrl with blank URL produces InvalidUrl blank error") {
            runTest {
                val viewModel = createFixture().build(CoroutineScope(testDispatcher))

                viewModel.submitUrl("")
                advanceUntilIdle()

                val error = viewModel.state.value.shouldBeInstanceOf<ServerConnectUiState.Error>()
                val invalid = error.error.shouldBeInstanceOf<ServerConnectError.InvalidUrl>()
                invalid.reason shouldBe "blank"
                invalid.message shouldBe "Please enter a server URL."
            }
        }

        test("submitUrl with whitespace-only URL produces InvalidUrl blank error") {
            runTest {
                val viewModel = createFixture().build(CoroutineScope(testDispatcher))

                viewModel.submitUrl("   ")
                advanceUntilIdle()

                val error = viewModel.state.value.shouldBeInstanceOf<ServerConnectUiState.Error>()
                val invalid = error.error.shouldBeInstanceOf<ServerConnectError.InvalidUrl>()
                invalid.reason shouldBe "blank"
            }
        }

        // Note: URL format validation is delegated to Ktor's URL parser.
        // Ktor is lenient with URLs, so specific malformed patterns would test
        // Ktor's behaviour rather than ours. Blank/whitespace covers our logic.

        // ========== clearError ==========

        test("clearError from Error returns to Idle") {
            runTest {
                val viewModel = createFixture().build(CoroutineScope(testDispatcher))
                viewModel.submitUrl("")
                advanceUntilIdle()
                checkIs<ServerConnectUiState.Error>(viewModel.state.value)

                viewModel.clearError()

                viewModel.state.value shouldBe ServerConnectUiState.Idle
            }
        }

        test("clearError from Idle is a no-op") {
            runTest {
                val viewModel = createFixture().build(CoroutineScope(testDispatcher))
                viewModel.state.value shouldBe ServerConnectUiState.Idle

                viewModel.clearError()

                viewModel.state.value shouldBe ServerConnectUiState.Idle
            }
        }

        // ========== Failure Classification (mapFailure) ==========
        //
        // mapFailure pattern-matches on the unified TransportError subtypes to surface
        // domain-specific user-facing messages rather than the generic VerificationFailed.
        // These tests pin the classification — substring matching against message text was
        // historically used here and silently broke under the body-level message convention
        // (constants don't contain throwable text); the type-pattern shape replaces it.

        test("verifyServer NetworkUnavailable maps to ServerNotReachable") {
            runTest {
                val fixture = createFixture()
                everySuspend { fixture.instanceRepository.verifyServer("https://example.com") } returns
                    AppResult.Failure(TransportError.NetworkUnavailable(debugInfo = "connection refused"))

                val viewModel = fixture.build(CoroutineScope(testDispatcher))
                viewModel.submitUrl("https://example.com")
                advanceUntilIdle()

                val errorState = viewModel.state.value.shouldBeInstanceOf<ServerConnectUiState.Error>()
                errorState.error.shouldBeInstanceOf<ServerConnectError.ServerNotReachable>()
            }
        }

        test("verifyServer Timeout maps to ServerNotReachable") {
            runTest {
                val fixture = createFixture()
                everySuspend { fixture.instanceRepository.verifyServer("https://example.com") } returns
                    AppResult.Failure(TransportError.Timeout(debugInfo = "connect timed out"))

                val viewModel = fixture.build(CoroutineScope(testDispatcher))
                viewModel.submitUrl("https://example.com")
                advanceUntilIdle()

                val errorState = viewModel.state.value.shouldBeInstanceOf<ServerConnectUiState.Error>()
                errorState.error.shouldBeInstanceOf<ServerConnectError.ServerNotReachable>()
            }
        }

        test("verifyServer DataMalformed maps to NotListenUpServer") {
            runTest {
                val fixture = createFixture()
                everySuspend { fixture.instanceRepository.verifyServer("https://example.com") } returns
                    AppResult.Failure(TransportError.DataMalformed(detail = "Unexpected token", debugInfo = "Unexpected token"))

                val viewModel = fixture.build(CoroutineScope(testDispatcher))
                viewModel.submitUrl("https://example.com")
                advanceUntilIdle()

                val errorState = viewModel.state.value.shouldBeInstanceOf<ServerConnectUiState.Error>()
                errorState.error.shouldBeInstanceOf<ServerConnectError.NotListenUpServer>()
            }
        }

        test("verifyServer Server4xx 404 maps to NotListenUpServer") {
            runTest {
                val fixture = createFixture()
                everySuspend { fixture.instanceRepository.verifyServer("https://example.com") } returns
                    AppResult.Failure(TransportError.Server4xx(statusCode = 404, debugInfo = "Not Found"))

                val viewModel = fixture.build(CoroutineScope(testDispatcher))
                viewModel.submitUrl("https://example.com")
                advanceUntilIdle()

                val errorState = viewModel.state.value.shouldBeInstanceOf<ServerConnectUiState.Error>()
                errorState.error.shouldBeInstanceOf<ServerConnectError.NotListenUpServer>()
            }
        }

        test("verifyServer Server4xx non-404 maps to VerificationFailed") {
            runTest {
                val fixture = createFixture()
                everySuspend { fixture.instanceRepository.verifyServer("https://example.com") } returns
                    AppResult.Failure(TransportError.Server4xx(statusCode = 401, debugInfo = "Unauthorized"))

                val viewModel = fixture.build(CoroutineScope(testDispatcher))
                viewModel.submitUrl("https://example.com")
                advanceUntilIdle()

                val errorState = viewModel.state.value.shouldBeInstanceOf<ServerConnectUiState.Error>()
                errorState.error.shouldBeInstanceOf<ServerConnectError.VerificationFailed>()
            }
        }

        test("verifyServer non-classifiable failure maps to VerificationFailed") {
            runTest {
                val fixture = createFixture()
                everySuspend { fixture.instanceRepository.verifyServer("https://example.com") } returns
                    AppResult.Failure(InternalError(debugInfo = "unexpected internal error"))

                val viewModel = fixture.build(CoroutineScope(testDispatcher))
                viewModel.submitUrl("https://example.com")
                advanceUntilIdle()

                val errorState = viewModel.state.value.shouldBeInstanceOf<ServerConnectUiState.Error>()
                errorState.error.shouldBeInstanceOf<ServerConnectError.VerificationFailed>()
            }
        }

        // ========== Success arms IP-follow ==========

        test("a successful verify persists the server's instance id so ConnectionCoordinator can IP-follow") {
            runTest {
                val fixture = createFixture()
                val verified =
                    VerifiedServer(
                        serverInfo =
                            ServerInfo(
                                name = "ListenUp",
                                version = "0.0.1",
                                apiVersion = "v1",
                                setupRequired = false,
                                registrationPolicy = RegistrationPolicy.OPEN,
                                instanceId = "inst-abc",
                            ),
                        verifiedUrl = "https://example.com",
                    )
                everySuspend { fixture.instanceRepository.verifyServer("https://example.com") } returns
                    AppResult.Success(verified)
                everySuspend { fixture.serverConfig.setServerUrl(any()) } returns Unit
                everySuspend { fixture.serverConfig.setConnectedServerId(any()) } returns Unit

                val viewModel = fixture.build(CoroutineScope(testDispatcher))
                viewModel.submitUrl("https://example.com")
                advanceUntilIdle()

                viewModel.state.value shouldBe ServerConnectUiState.Verified
                // Without this, a manually-entered server has a null connectedServerId and never
                // relocates on a LAN address change (the IP-follow gap for non-picker connects).
                verifySuspend { fixture.serverConfig.setConnectedServerId("inst-abc") }
            }
        }
    })
