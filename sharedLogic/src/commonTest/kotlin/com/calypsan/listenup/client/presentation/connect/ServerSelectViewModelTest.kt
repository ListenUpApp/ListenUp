package com.calypsan.listenup.client.presentation.connect

import app.cash.turbine.test
import com.calypsan.listenup.api.error.ServerConnectError
import com.calypsan.listenup.core.ServerUrl
import com.calypsan.listenup.core.error.ErrorBus
import com.calypsan.listenup.client.domain.model.Server
import com.calypsan.listenup.client.domain.model.ServerWithStatus
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.domain.repository.ServerRepository
import dev.mokkery.answering.returns
import dev.mokkery.answering.throws
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class ServerSelectViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private fun createServer(
        id: String = "server-1",
        name: String = "Test Server",
        localUrl: String = "http://192.168.1.100:8080",
    ) = Server(
        id = id,
        name = name,
        apiVersion = "v1",
        serverVersion = "1.0.0",
        localUrl = localUrl,
        remoteUrl = null,
        isActive = false,
        lastSeenAt = 0,
    )

    private fun createServerWithStatus(
        server: Server = createServer(),
        isOnline: Boolean = true,
    ) = ServerWithStatus(
        server = server,
        isOnline = isOnline,
    )

    /** Keep the VM's WhileSubscribed state flow hot for the duration of the test. */
    private fun TestScope.keepStateHot(viewModel: ServerSelectViewModel) {
        backgroundScope.launch { viewModel.state.collect { } }
    }

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is Discovering with empty servers`() =
        runTest {
            val serverRepository: ServerRepository = mock()
            val serverConfig: ServerConfig = mock()
            val instanceRepository: InstanceRepository = mock()
            every { serverRepository.observeServers() } returns
                kotlinx.coroutines.flow.flow { /* never emits */ }

            val viewModel = ServerSelectViewModel(serverRepository, serverConfig, instanceRepository, errorBus = ErrorBus())
            keepStateHot(viewModel)
            advanceUntilIdle()

            val state = viewModel.state.value
            val discovering = assertIs<ServerSelectUiState.Discovering>(state)
            assertEquals(emptyList(), discovering.servers)
        }

    @Test
    fun `LocalNetworkPermissionGranted starts server discovery`() =
        runTest {
            val serverRepository: ServerRepository = mock()
            val serverConfig: ServerConfig = mock()
            val instanceRepository: InstanceRepository = mock()
            every { serverRepository.observeServers() } returns MutableStateFlow(emptyList())
            every { serverRepository.startDiscovery() } returns Unit

            val viewModel = ServerSelectViewModel(serverRepository, serverConfig, instanceRepository, errorBus = ErrorBus())
            keepStateHot(viewModel)

            viewModel.onEvent(ServerSelectUiEvent.LocalNetworkPermissionGranted)
            advanceUntilIdle()

            verify { serverRepository.startDiscovery() }
        }

    @Test
    fun `LocalNetworkPermissionGranted then observeServers emission transitions Discovering to Ready`() =
        runTest {
            val serverRepository: ServerRepository = mock()
            val serverConfig: ServerConfig = mock()
            val instanceRepository: InstanceRepository = mock()
            val serversFlow = MutableStateFlow<List<ServerWithStatus>>(emptyList())
            every { serverRepository.observeServers() } returns serversFlow
            every { serverRepository.startDiscovery() } returns Unit

            val viewModel = ServerSelectViewModel(serverRepository, serverConfig, instanceRepository, errorBus = ErrorBus())
            keepStateHot(viewModel)

            viewModel.onEvent(ServerSelectUiEvent.LocalNetworkPermissionGranted)
            advanceUntilIdle()

            // Initial emission (empty) flips to Ready
            assertIs<ServerSelectUiState.Ready>(viewModel.state.value)

            val servers = listOf(createServerWithStatus())
            serversFlow.value = servers
            advanceUntilIdle()

            val ready = assertIs<ServerSelectUiState.Ready>(viewModel.state.value)
            assertEquals(1, ready.servers.size)
        }

    @Test
    fun `LocalNetworkPermissionDenied emits error and navigates to manual entry`() =
        runTest {
            val serverRepository: ServerRepository = mock()
            val serverConfig: ServerConfig = mock()
            val instanceRepository: InstanceRepository = mock()
            every { serverRepository.observeServers() } returns MutableStateFlow(emptyList())
            val errorBus = ErrorBus()

            val viewModel = ServerSelectViewModel(serverRepository, serverConfig, instanceRepository, errorBus = errorBus)
            keepStateHot(viewModel)
            advanceUntilIdle()

            errorBus.errors.test {
                viewModel.onEvent(ServerSelectUiEvent.LocalNetworkPermissionDenied)
                advanceUntilIdle()
                assertIs<ServerConnectError.LocalNetworkPermissionDenied>(awaitItem())
            }

            viewModel.navigationEvents.test {
                // Navigation event was already trySend'd synchronously, should be buffered
                assertEquals(ServerSelectViewModel.NavigationEvent.GoToManualEntry, awaitItem())
            }

            // Discovery must never start on the denial path.
            verify(VerifyMode.not) { serverRepository.startDiscovery() }
        }

    @Test
    fun `ManualEntryClicked emits GoToManualEntry navigation event`() =
        runTest {
            val serverRepository: ServerRepository = mock()
            val serverConfig: ServerConfig = mock()
            val instanceRepository: InstanceRepository = mock()
            every { serverRepository.observeServers() } returns MutableStateFlow(emptyList())
            every { serverRepository.startDiscovery() } returns Unit

            val viewModel = ServerSelectViewModel(serverRepository, serverConfig, instanceRepository, errorBus = ErrorBus())
            keepStateHot(viewModel)
            advanceUntilIdle()

            viewModel.navigationEvents.test {
                viewModel.onEvent(ServerSelectUiEvent.ManualEntryClicked)
                advanceUntilIdle()
                assertEquals(ServerSelectViewModel.NavigationEvent.GoToManualEntry, awaitItem())
            }
        }

    @Test
    fun `RefreshClicked stops and restarts discovery`() =
        runTest {
            val serverRepository: ServerRepository = mock()
            val serverConfig: ServerConfig = mock()
            val instanceRepository: InstanceRepository = mock()
            every { serverRepository.observeServers() } returns MutableStateFlow(emptyList())
            every { serverRepository.startDiscovery() } returns Unit
            every { serverRepository.stopDiscovery() } returns Unit

            val viewModel = ServerSelectViewModel(serverRepository, serverConfig, instanceRepository, errorBus = ErrorBus())
            keepStateHot(viewModel)
            viewModel.onEvent(ServerSelectUiEvent.LocalNetworkPermissionGranted)
            advanceUntilIdle()

            viewModel.onEvent(ServerSelectUiEvent.RefreshClicked)
            advanceUntilIdle()

            verify { serverRepository.stopDiscovery() }
        }

    @Test
    fun `ServerSelected activates server and emits navigation`() =
        runTest {
            val serverRepository: ServerRepository = mock()
            val serverConfig: ServerConfig = mock()
            val instanceRepository: InstanceRepository = mock()
            val server = createServer()
            every { serverRepository.observeServers() } returns MutableStateFlow(emptyList())
            every { serverRepository.startDiscovery() } returns Unit
            everySuspend { instanceRepository.findReachableUrl(any()) } returns server.localUrl
            everySuspend { serverConfig.setServerUrl(any()) } returns Unit
            everySuspend { serverConfig.setConnectedServerId(any()) } returns Unit

            val viewModel = ServerSelectViewModel(serverRepository, serverConfig, instanceRepository, errorBus = ErrorBus())
            keepStateHot(viewModel)
            viewModel.onEvent(ServerSelectUiEvent.LocalNetworkPermissionGranted)
            advanceUntilIdle()

            viewModel.navigationEvents.test {
                viewModel.onEvent(ServerSelectUiEvent.ServerSelected(createServerWithStatus(server)))
                advanceUntilIdle()

                verifySuspend { serverConfig.setServerUrl(ServerUrl(server.localUrl!!)) }
                verifySuspend { serverConfig.setConnectedServerId(server.id) }
                assertEquals(ServerSelectViewModel.NavigationEvent.ServerActivated, awaitItem())
            }

            // After success, overlay cleared → Ready
            assertIs<ServerSelectUiState.Ready>(viewModel.state.value)
        }

    @Test
    fun `ServerSelected failure transitions to Error state`() =
        runTest {
            val serverRepository: ServerRepository = mock()
            val serverConfig: ServerConfig = mock()
            val instanceRepository: InstanceRepository = mock()
            val server = createServer()
            every { serverRepository.observeServers() } returns MutableStateFlow(emptyList())
            every { serverRepository.startDiscovery() } returns Unit
            everySuspend { instanceRepository.findReachableUrl(any()) } throws RuntimeException("Failed")

            val viewModel = ServerSelectViewModel(serverRepository, serverConfig, instanceRepository, errorBus = ErrorBus())
            keepStateHot(viewModel)
            viewModel.onEvent(ServerSelectUiEvent.LocalNetworkPermissionGranted)
            advanceUntilIdle()

            viewModel.onEvent(ServerSelectUiEvent.ServerSelected(createServerWithStatus(server)))
            advanceUntilIdle()

            val error = assertIs<ServerSelectUiState.Error>(viewModel.state.value)
            assertEquals(server.id, error.selectedServerId)
        }

    @Test
    fun `ErrorDismissed transitions from Error back to Ready`() =
        runTest {
            val serverRepository: ServerRepository = mock()
            val serverConfig: ServerConfig = mock()
            val instanceRepository: InstanceRepository = mock()
            val server = createServer()
            every { serverRepository.observeServers() } returns MutableStateFlow(emptyList())
            every { serverRepository.startDiscovery() } returns Unit
            everySuspend { instanceRepository.findReachableUrl(any()) } throws RuntimeException("Failed")

            val viewModel = ServerSelectViewModel(serverRepository, serverConfig, instanceRepository, errorBus = ErrorBus())
            keepStateHot(viewModel)
            viewModel.onEvent(ServerSelectUiEvent.LocalNetworkPermissionGranted)
            advanceUntilIdle()
            viewModel.onEvent(ServerSelectUiEvent.ServerSelected(createServerWithStatus(server)))
            advanceUntilIdle()
            assertIs<ServerSelectUiState.Error>(viewModel.state.value)

            viewModel.onEvent(ServerSelectUiEvent.ErrorDismissed)
            advanceUntilIdle()

            assertIs<ServerSelectUiState.Ready>(viewModel.state.value)
        }
}
