package com.calypsan.listenup.client.presentation.connect

import com.calypsan.listenup.client.domain.usecase.auth.AdoptServerUseCase
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
import com.calypsan.listenup.client.test.fake.FakeLocalNetworkAccess
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
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
            val localNetworkAccess = FakeLocalNetworkAccess()

            fun build(appScope: CoroutineScope): ServerConnectViewModel =
                ServerConnectViewModel(
                    adoptServer = AdoptServerUseCase(serverConfig) {},
                    instanceRepository = instanceRepository,
                    localNetworkAccess = localNetworkAccess,
                    appScope = appScope,
                )
        }

        fun createFixture(): TestFixture = TestFixture()

        fun TestFixture.stubAdoption() {
            everySuspend { serverConfig.setServerUrl(any()) } returns Unit
            everySuspend { serverConfig.getConnectedServerId() } returns null
            everySuspend { serverConfig.getLibraryServerId() } returns null
            everySuspend { serverConfig.setLibraryServerId(any()) } returns Unit
            everySuspend { serverConfig.setConnectedServerId(any()) } returns Unit
        }

        fun verifiedServer(url: String) =
            VerifiedServer(
                serverInfo =
                    ServerInfo(
                        name = "ListenUp",
                        version = "0.0.1",
                        apiVersion = "v1",
                        setupRequired = false,
                        registrationPolicy = RegistrationPolicy.OPEN,
                        instanceId = "inst-lan",
                    ),
                verifiedUrl = url,
            )

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

        // ========== Local network permission (Android 17 / iOS) ==========
        //
        // A connect the OS blocks for want of local-network permission times out exactly like a
        // server that is switched off. The gate is asked only after such a failure, so a server
        // reached over an ungated VPN is never blamed on the permission.

        test("a timeout the local-network gate explains maps to LocalNetworkPermissionDenied") {
            runTest {
                val fixture = createFixture()
                fixture.localNetworkAccess.denied = true
                everySuspend { fixture.instanceRepository.verifyServer("http://192.168.1.5:8080") } returns
                    AppResult.Failure(TransportError.Timeout(debugInfo = "connect timed out"))

                val viewModel = fixture.build(CoroutineScope(testDispatcher))
                viewModel.submitUrl("http://192.168.1.5:8080")
                advanceUntilIdle()

                val errorState = viewModel.state.value.shouldBeInstanceOf<ServerConnectUiState.Error>()
                errorState.error.shouldBeInstanceOf<ServerConnectError.LocalNetworkPermissionDenied>()
            }
        }

        test("an unavailable network the local-network gate explains maps to LocalNetworkPermissionDenied") {
            runTest {
                val fixture = createFixture()
                fixture.localNetworkAccess.denied = true
                everySuspend { fixture.instanceRepository.verifyServer("http://192.168.1.5:8080") } returns
                    AppResult.Failure(TransportError.NetworkUnavailable(debugInfo = "connection refused"))

                val viewModel = fixture.build(CoroutineScope(testDispatcher))
                viewModel.submitUrl("http://192.168.1.5:8080")
                advanceUntilIdle()

                val errorState = viewModel.state.value.shouldBeInstanceOf<ServerConnectUiState.Error>()
                errorState.error.shouldBeInstanceOf<ServerConnectError.LocalNetworkPermissionDenied>()
            }
        }

        test("a timeout the gate does not explain stays ServerNotReachable") {
            runTest {
                val fixture = createFixture()
                fixture.localNetworkAccess.denied = false
                everySuspend { fixture.instanceRepository.verifyServer("http://192.168.1.5:8080") } returns
                    AppResult.Failure(TransportError.Timeout(debugInfo = "connect timed out"))

                val viewModel = fixture.build(CoroutineScope(testDispatcher))
                viewModel.submitUrl("http://192.168.1.5:8080")
                advanceUntilIdle()

                val errorState = viewModel.state.value.shouldBeInstanceOf<ServerConnectUiState.Error>()
                errorState.error.shouldBeInstanceOf<ServerConnectError.ServerNotReachable>()
                fixture.localNetworkAccess.queries shouldBe listOf("192.168.1.5" to 8080)
            }
        }

        test("a server that answered is never blamed on the permission, and the gate is not consulted") {
            runTest {
                val fixture = createFixture()
                fixture.localNetworkAccess.denied = true
                everySuspend { fixture.instanceRepository.verifyServer("http://192.168.1.5:8080") } returns
                    AppResult.Failure(TransportError.Server4xx(statusCode = 404, debugInfo = "Not Found"))

                val viewModel = fixture.build(CoroutineScope(testDispatcher))
                viewModel.submitUrl("http://192.168.1.5:8080")
                advanceUntilIdle()

                val errorState = viewModel.state.value.shouldBeInstanceOf<ServerConnectUiState.Error>()
                errorState.error.shouldBeInstanceOf<ServerConnectError.NotListenUpServer>()
                fixture.localNetworkAccess.queries.shouldBeEmpty()
            }
        }

        test("a URL typed without a scheme reaches the gate as its host and port") {
            runTest {
                val fixture = createFixture()
                everySuspend { fixture.instanceRepository.verifyServer("192.168.1.5:8080") } returns
                    AppResult.Failure(TransportError.Timeout(debugInfo = "connect timed out"))

                val viewModel = fixture.build(CoroutineScope(testDispatcher))
                viewModel.submitUrl("192.168.1.5:8080")
                advanceUntilIdle()

                fixture.localNetworkAccess.queries shouldBe listOf("192.168.1.5" to 8080)
            }
        }

        test("retryAfterLocalNetworkGrant re-runs the attempt the permission blocked") {
            runTest {
                val fixture = createFixture()
                fixture.localNetworkAccess.denied = true
                var serverAnswers = false
                everySuspend { fixture.instanceRepository.verifyServer("http://192.168.1.5:8080") } calls {
                    if (serverAnswers) {
                        AppResult.Success(verifiedServer("http://192.168.1.5:8080"))
                    } else {
                        AppResult.Failure(TransportError.Timeout(debugInfo = "connect timed out"))
                    }
                }
                fixture.stubAdoption()

                val viewModel = fixture.build(CoroutineScope(testDispatcher))
                viewModel.submitUrl("http://192.168.1.5:8080")
                advanceUntilIdle()
                viewModel.state.value
                    .shouldBeInstanceOf<ServerConnectUiState.Error>()
                    .error
                    .shouldBeInstanceOf<ServerConnectError.LocalNetworkPermissionDenied>()

                // The user allows access in the dialog or in Settings and comes back.
                fixture.localNetworkAccess.denied = false
                serverAnswers = true
                viewModel.retryAfterLocalNetworkGrant()
                advanceUntilIdle()

                viewModel.state.value shouldBe ServerConnectUiState.Verified
            }
        }

        test("retryAfterLocalNetworkGrant leaves any other failure alone") {
            runTest {
                val fixture = createFixture()
                everySuspend { fixture.instanceRepository.verifyServer("http://192.168.1.5:8080") } returns
                    AppResult.Failure(TransportError.Timeout(debugInfo = "connect timed out"))

                val viewModel = fixture.build(CoroutineScope(testDispatcher))
                viewModel.submitUrl("http://192.168.1.5:8080")
                advanceUntilIdle()

                viewModel.retryAfterLocalNetworkGrant()
                advanceUntilIdle()

                viewModel.state.value
                    .shouldBeInstanceOf<ServerConnectUiState.Error>()
                    .error
                    .shouldBeInstanceOf<ServerConnectError.ServerNotReachable>()
                verifySuspend(VerifyMode.exactly(1)) { fixture.instanceRepository.verifyServer(any()) }
            }
        }

        test("two retries in the same moment verify the server once") {
            runTest {
                val fixture = createFixture()
                fixture.localNetworkAccess.denied = true
                everySuspend { fixture.instanceRepository.verifyServer("http://192.168.1.5:8080") } returns
                    AppResult.Failure(TransportError.Timeout(debugInfo = "connect timed out"))

                val viewModel = fixture.build(CoroutineScope(testDispatcher))
                viewModel.submitUrl("http://192.168.1.5:8080")
                advanceUntilIdle()

                // Android's resume effect and the grant callback can both fire before either retry
                // has been dispatched; only one may reach the network.
                viewModel.retryAfterLocalNetworkGrant()
                viewModel.retryAfterLocalNetworkGrant()
                advanceUntilIdle()

                verifySuspend(VerifyMode.exactly(2)) { fixture.instanceRepository.verifyServer(any()) }
            }
        }

        test("submitUrl reports Verifying before the attempt is dispatched") {
            runTest {
                val fixture = createFixture()
                everySuspend { fixture.instanceRepository.verifyServer("http://192.168.1.5:8080") } returns
                    AppResult.Failure(TransportError.Timeout(debugInfo = "connect timed out"))
                val viewModel = fixture.build(CoroutineScope(testDispatcher))

                viewModel.submitUrl("http://192.168.1.5:8080")

                viewModel.state.value shouldBe ServerConnectUiState.Verifying
            }
        }

        test("a gate that fails to answer leaves the screen on ServerNotReachable, not spinning") {
            runTest {
                val fixture = createFixture()
                fixture.localNetworkAccess.failure = IllegalStateException("probe exploded")
                everySuspend { fixture.instanceRepository.verifyServer("http://192.168.1.5:8080") } returns
                    AppResult.Failure(TransportError.Timeout(debugInfo = "connect timed out"))

                val viewModel = fixture.build(CoroutineScope(testDispatcher))
                viewModel.submitUrl("http://192.168.1.5:8080")
                advanceUntilIdle()

                viewModel.state.value
                    .shouldBeInstanceOf<ServerConnectUiState.Error>()
                    .error
                    .shouldBeInstanceOf<ServerConnectError.ServerNotReachable>()
            }
        }

        test("isClearlyRemoteAddress tells a remote server from one that might be local") {
            val viewModel = createFixture().build(CoroutineScope(testDispatcher))

            viewModel.isClearlyRemoteAddress("https://yourname.listenup.app") shouldBe true
            viewModel.isClearlyRemoteAddress("nas.lan") shouldBe false
        }

        test("retryAfterLocalNetworkGrant before any attempt does nothing") {
            runTest {
                val fixture = createFixture()
                val viewModel = fixture.build(CoroutineScope(testDispatcher))

                viewModel.retryAfterLocalNetworkGrant()
                advanceUntilIdle()

                viewModel.state.value shouldBe ServerConnectUiState.Idle
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
                everySuspend { fixture.serverConfig.getConnectedServerId() } returns null
                everySuspend { fixture.serverConfig.getLibraryServerId() } returns null
                everySuspend { fixture.serverConfig.setLibraryServerId(any()) } returns Unit
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
