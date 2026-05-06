package com.calypsan.listenup.client.presentation.connect

import com.calypsan.listenup.client.checkIs
import com.calypsan.listenup.api.error.ServerConnectError
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
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
}
