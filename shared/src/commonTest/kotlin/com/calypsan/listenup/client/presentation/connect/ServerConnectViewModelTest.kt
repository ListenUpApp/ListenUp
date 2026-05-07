package com.calypsan.listenup.client.presentation.connect

import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.error.ServerConnectError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.client.checkIs
import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.mock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Tests for ServerConnectViewModel.
 *
 * Covers local validation and state-machine behaviour. Network verification
 * paths are exercised indirectly through the validation fallthrough; the
 * real InstanceRepository is not injected here because its construction
 * involves HttpClient internals that would require integration testing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerConnectViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private class TestFixture {
        val serverConfig: ServerConfig = mock()
        val instanceRepository: InstanceRepository = mock()

        fun build(): ServerConnectViewModel =
            ServerConnectViewModel(
                serverConfig = serverConfig,
                instanceRepository = instanceRepository,
            )
    }

    private fun createFixture(): TestFixture = TestFixture()

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ========== Initial State ==========

    @Test
    fun `initial state is Idle`() =
        runTest {
            val viewModel = createFixture().build()

            assertEquals(ServerConnectUiState.Idle, viewModel.state.value)
        }

    // ========== URL Validation ==========

    @Test
    fun `submitUrl with blank URL produces InvalidUrl blank error`() =
        runTest {
            val viewModel = createFixture().build()

            viewModel.submitUrl("")
            advanceUntilIdle()

            val error = assertIs<ServerConnectUiState.Error>(viewModel.state.value)
            val invalid = assertIs<ServerConnectError.InvalidUrl>(error.error)
            assertEquals("blank", invalid.reason)
            assertEquals("Please enter a server URL.", invalid.message)
        }

    @Test
    fun `submitUrl with whitespace-only URL produces InvalidUrl blank error`() =
        runTest {
            val viewModel = createFixture().build()

            viewModel.submitUrl("   ")
            advanceUntilIdle()

            val error = assertIs<ServerConnectUiState.Error>(viewModel.state.value)
            val invalid = assertIs<ServerConnectError.InvalidUrl>(error.error)
            assertEquals("blank", invalid.reason)
        }

    // Note: URL format validation is delegated to Ktor's URL parser.
    // Ktor is lenient with URLs, so specific malformed patterns would test
    // Ktor's behaviour rather than ours. Blank/whitespace covers our logic.

    // ========== clearError ==========

    @Test
    fun `clearError from Error returns to Idle`() =
        runTest {
            val viewModel = createFixture().build()
            viewModel.submitUrl("")
            advanceUntilIdle()
            checkIs<ServerConnectUiState.Error>(viewModel.state.value)

            viewModel.clearError()

            assertEquals(ServerConnectUiState.Idle, viewModel.state.value)
        }

    @Test
    fun `clearError from Idle is a no-op`() =
        runTest {
            val viewModel = createFixture().build()
            assertEquals(ServerConnectUiState.Idle, viewModel.state.value)

            viewModel.clearError()

            assertEquals(ServerConnectUiState.Idle, viewModel.state.value)
        }

    // ========== Failure Classification (mapFailure) ==========
    //
    // mapFailure pattern-matches on the unified TransportError subtypes to surface
    // domain-specific user-facing messages rather than the generic VerificationFailed.
    // These tests pin the classification — substring matching against message text was
    // historically used here and silently broke under the body-level message convention
    // (constants don't contain throwable text); the type-pattern shape replaces it.

    @Test
    fun `verifyServer NetworkUnavailable maps to ServerNotReachable`() =
        runTest {
            val fixture = createFixture()
            everySuspend { fixture.instanceRepository.verifyServer("https://example.com") } returns
                AppResult.Failure(TransportError.NetworkUnavailable(debugInfo = "connection refused"))

            val viewModel = fixture.build()
            viewModel.submitUrl("https://example.com")
            advanceUntilIdle()

            val errorState = assertIs<ServerConnectUiState.Error>(viewModel.state.value)
            assertIs<ServerConnectError.ServerNotReachable>(errorState.error)
        }

    @Test
    fun `verifyServer Timeout maps to ServerNotReachable`() =
        runTest {
            val fixture = createFixture()
            everySuspend { fixture.instanceRepository.verifyServer("https://example.com") } returns
                AppResult.Failure(TransportError.Timeout(debugInfo = "connect timed out"))

            val viewModel = fixture.build()
            viewModel.submitUrl("https://example.com")
            advanceUntilIdle()

            val errorState = assertIs<ServerConnectUiState.Error>(viewModel.state.value)
            assertIs<ServerConnectError.ServerNotReachable>(errorState.error)
        }

    @Test
    fun `verifyServer DataMalformed maps to NotListenUpServer`() =
        runTest {
            val fixture = createFixture()
            everySuspend { fixture.instanceRepository.verifyServer("https://example.com") } returns
                AppResult.Failure(TransportError.DataMalformed(detail = "Unexpected token", debugInfo = "Unexpected token"))

            val viewModel = fixture.build()
            viewModel.submitUrl("https://example.com")
            advanceUntilIdle()

            val errorState = assertIs<ServerConnectUiState.Error>(viewModel.state.value)
            assertIs<ServerConnectError.NotListenUpServer>(errorState.error)
        }

    @Test
    fun `verifyServer Server4xx 404 maps to NotListenUpServer`() =
        runTest {
            val fixture = createFixture()
            everySuspend { fixture.instanceRepository.verifyServer("https://example.com") } returns
                AppResult.Failure(TransportError.Server4xx(statusCode = 404, debugInfo = "Not Found"))

            val viewModel = fixture.build()
            viewModel.submitUrl("https://example.com")
            advanceUntilIdle()

            val errorState = assertIs<ServerConnectUiState.Error>(viewModel.state.value)
            assertIs<ServerConnectError.NotListenUpServer>(errorState.error)
        }

    @Test
    fun `verifyServer Server4xx non-404 maps to VerificationFailed`() =
        runTest {
            val fixture = createFixture()
            everySuspend { fixture.instanceRepository.verifyServer("https://example.com") } returns
                AppResult.Failure(TransportError.Server4xx(statusCode = 401, debugInfo = "Unauthorized"))

            val viewModel = fixture.build()
            viewModel.submitUrl("https://example.com")
            advanceUntilIdle()

            val errorState = assertIs<ServerConnectUiState.Error>(viewModel.state.value)
            assertIs<ServerConnectError.VerificationFailed>(errorState.error)
        }

    @Test
    fun `verifyServer non-classifiable failure maps to VerificationFailed`() =
        runTest {
            val fixture = createFixture()
            everySuspend { fixture.instanceRepository.verifyServer("https://example.com") } returns
                AppResult.Failure(InternalError(debugInfo = "unexpected internal error"))

            val viewModel = fixture.build()
            viewModel.submitUrl("https://example.com")
            advanceUntilIdle()

            val errorState = assertIs<ServerConnectUiState.Error>(viewModel.state.value)
            assertIs<ServerConnectError.VerificationFailed>(errorState.error)
        }
}
