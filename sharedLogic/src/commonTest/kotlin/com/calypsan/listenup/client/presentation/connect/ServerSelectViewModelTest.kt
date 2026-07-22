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
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
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

@OptIn(ExperimentalCoroutinesApi::class)
class ServerSelectViewModelTest :
    FunSpec({
        val testDispatcher = StandardTestDispatcher()

        fun createServer(
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

        fun createServerWithStatus(
            server: Server = createServer(),
            isOnline: Boolean = true,
        ) = ServerWithStatus(
            server = server,
            isOnline = isOnline,
        )

        // Keep the VM's WhileSubscribed state flow hot for the duration of the test.
        fun TestScope.keepStateHot(viewModel: ServerSelectViewModel) {
            backgroundScope.launch { viewModel.state.collect { } }
        }

        beforeTest {
            Dispatchers.setMain(testDispatcher)
        }

        afterTest {
            Dispatchers.resetMain()
        }

        test("initial state is Discovering with empty servers") {
            runTest {
                val serverRepository: ServerRepository = mock()
                val serverConfig: ServerConfig = mock()
                val instanceRepository: InstanceRepository = mock()
                every { serverRepository.observeServers() } returns
                    kotlinx.coroutines.flow.flow { /* never emits */ }

                val viewModel = ServerSelectViewModel(serverRepository, serverConfig, instanceRepository, errorBus = ErrorBus(), appScope = CoroutineScope(testDispatcher))
                keepStateHot(viewModel)
                advanceUntilIdle()

                val state = viewModel.state.value
                val discovering = state.shouldBeInstanceOf<ServerSelectUiState.Discovering>()
                discovering.servers shouldBe emptyList()
            }
        }

        test("close stops mDNS discovery and is idempotent (#1192 iOS teardown)") {
            runTest {
                val serverRepository: ServerRepository = mock()
                val serverConfig: ServerConfig = mock()
                val instanceRepository: InstanceRepository = mock()
                every { serverRepository.observeServers() } returns MutableStateFlow(emptyList())
                every { serverRepository.stopDiscovery() } returns Unit

                val viewModel = ServerSelectViewModel(serverRepository, serverConfig, instanceRepository, errorBus = ErrorBus(), appScope = CoroutineScope(testDispatcher))
                keepStateHot(viewModel)
                advanceUntilIdle()

                // iOS has no ViewModelStore; ServerSelectViewModelWrapper's isolated deinit calls
                // close() so the mDNS discovery doesn't announce/scan forever after the screen goes.
                viewModel.close()
                viewModel.close() // idempotent — the second call must not stop discovery again

                verify(VerifyMode.exactly(1)) { serverRepository.stopDiscovery() }
            }
        }

        test("LocalNetworkPermissionGranted starts server discovery") {
            runTest {
                val serverRepository: ServerRepository = mock()
                val serverConfig: ServerConfig = mock()
                val instanceRepository: InstanceRepository = mock()
                every { serverRepository.observeServers() } returns MutableStateFlow(emptyList())
                every { serverRepository.startDiscovery() } returns Unit

                val viewModel = ServerSelectViewModel(serverRepository, serverConfig, instanceRepository, errorBus = ErrorBus(), appScope = CoroutineScope(testDispatcher))
                keepStateHot(viewModel)

                viewModel.onEvent(ServerSelectUiEvent.LocalNetworkPermissionGranted)
                advanceUntilIdle()

                verify { serverRepository.startDiscovery() }
            }
        }

        test("LocalNetworkPermissionGranted then observeServers emission transitions Discovering to Ready") {
            runTest {
                val serverRepository: ServerRepository = mock()
                val serverConfig: ServerConfig = mock()
                val instanceRepository: InstanceRepository = mock()
                val serversFlow = MutableStateFlow<List<ServerWithStatus>>(emptyList())
                every { serverRepository.observeServers() } returns serversFlow
                every { serverRepository.startDiscovery() } returns Unit

                val viewModel = ServerSelectViewModel(serverRepository, serverConfig, instanceRepository, errorBus = ErrorBus(), appScope = CoroutineScope(testDispatcher))
                keepStateHot(viewModel)

                viewModel.onEvent(ServerSelectUiEvent.LocalNetworkPermissionGranted)
                advanceUntilIdle()

                // Initial emission (empty) flips to Ready
                viewModel.state.value.shouldBeInstanceOf<ServerSelectUiState.Ready>()

                val servers = listOf(createServerWithStatus())
                serversFlow.value = servers
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<ServerSelectUiState.Ready>()
                ready.servers.size shouldBe 1
            }
        }

        test("LocalNetworkPermissionDenied emits error and navigates to manual entry") {
            runTest {
                val serverRepository: ServerRepository = mock()
                val serverConfig: ServerConfig = mock()
                val instanceRepository: InstanceRepository = mock()
                every { serverRepository.observeServers() } returns MutableStateFlow(emptyList())
                val errorBus = ErrorBus()

                val viewModel = ServerSelectViewModel(serverRepository, serverConfig, instanceRepository, errorBus = errorBus, appScope = CoroutineScope(testDispatcher))
                keepStateHot(viewModel)
                advanceUntilIdle()

                errorBus.errors.test {
                    viewModel.onEvent(ServerSelectUiEvent.LocalNetworkPermissionDenied)
                    advanceUntilIdle()
                    awaitItem().shouldBeInstanceOf<ServerConnectError.LocalNetworkPermissionDenied>()
                }

                viewModel.navigationEvents.test {
                    // Navigation event was already trySend'd synchronously, should be buffered
                    awaitItem() shouldBe ServerSelectViewModel.NavigationEvent.GoToManualEntry
                }

                // Discovery must never start on the denial path.
                verify(VerifyMode.not) { serverRepository.startDiscovery() }
            }
        }

        test("ManualEntryClicked emits GoToManualEntry navigation event") {
            runTest {
                val serverRepository: ServerRepository = mock()
                val serverConfig: ServerConfig = mock()
                val instanceRepository: InstanceRepository = mock()
                every { serverRepository.observeServers() } returns MutableStateFlow(emptyList())
                every { serverRepository.startDiscovery() } returns Unit

                val viewModel = ServerSelectViewModel(serverRepository, serverConfig, instanceRepository, errorBus = ErrorBus(), appScope = CoroutineScope(testDispatcher))
                keepStateHot(viewModel)
                advanceUntilIdle()

                viewModel.navigationEvents.test {
                    viewModel.onEvent(ServerSelectUiEvent.ManualEntryClicked)
                    advanceUntilIdle()
                    awaitItem() shouldBe ServerSelectViewModel.NavigationEvent.GoToManualEntry
                }
            }
        }

        test("RefreshClicked stops and restarts discovery") {
            runTest {
                val serverRepository: ServerRepository = mock()
                val serverConfig: ServerConfig = mock()
                val instanceRepository: InstanceRepository = mock()
                every { serverRepository.observeServers() } returns MutableStateFlow(emptyList())
                every { serverRepository.startDiscovery() } returns Unit
                every { serverRepository.stopDiscovery() } returns Unit

                val viewModel = ServerSelectViewModel(serverRepository, serverConfig, instanceRepository, errorBus = ErrorBus(), appScope = CoroutineScope(testDispatcher))
                keepStateHot(viewModel)
                viewModel.onEvent(ServerSelectUiEvent.LocalNetworkPermissionGranted)
                advanceUntilIdle()

                viewModel.onEvent(ServerSelectUiEvent.RefreshClicked)
                advanceUntilIdle()

                verify { serverRepository.stopDiscovery() }
            }
        }

        test("ServerSelected activates server and emits navigation") {
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

                val viewModel = ServerSelectViewModel(serverRepository, serverConfig, instanceRepository, errorBus = ErrorBus(), appScope = CoroutineScope(testDispatcher))
                keepStateHot(viewModel)
                viewModel.onEvent(ServerSelectUiEvent.LocalNetworkPermissionGranted)
                advanceUntilIdle()

                viewModel.navigationEvents.test {
                    viewModel.onEvent(ServerSelectUiEvent.ServerSelected(createServerWithStatus(server)))
                    advanceUntilIdle()

                    verifySuspend { serverConfig.setServerUrl(ServerUrl(server.localUrl!!)) }
                    verifySuspend { serverConfig.setConnectedServerId(server.id) }
                    awaitItem() shouldBe ServerSelectViewModel.NavigationEvent.ServerActivated
                }

                // After success, overlay cleared → Ready
                viewModel.state.value.shouldBeInstanceOf<ServerSelectUiState.Ready>()
            }
        }

        test("ServerSelected tries every resolved local URL and activates the reachable fallback") {
            runTest {
                val serverRepository: ServerRepository = mock()
                val serverConfig: ServerConfig = mock()
                val instanceRepository: InstanceRepository = mock()
                val primary = "http://192.168.86.39:8080"
                val fallback = "http://192.168.86.37:8080"
                val server = createServer(localUrl = primary).copy(localUrls = listOf(primary, fallback))
                every { serverRepository.observeServers() } returns MutableStateFlow(emptyList())
                every { serverRepository.startDiscovery() } returns Unit
                everySuspend { instanceRepository.findReachableUrl(any()) } returns fallback
                everySuspend { serverConfig.setServerUrl(any()) } returns Unit
                everySuspend { serverConfig.setConnectedServerId(any()) } returns Unit

                val viewModel = ServerSelectViewModel(serverRepository, serverConfig, instanceRepository, errorBus = ErrorBus(), appScope = CoroutineScope(testDispatcher))
                keepStateHot(viewModel)
                viewModel.onEvent(ServerSelectUiEvent.LocalNetworkPermissionGranted)
                advanceUntilIdle()

                viewModel.onEvent(ServerSelectUiEvent.ServerSelected(createServerWithStatus(server)))
                advanceUntilIdle()

                // The whole candidate list (best-first) reaches reachability, and the reachable
                // fallback — not the unreachable primary — becomes the active URL.
                verifySuspend { instanceRepository.findReachableUrl(listOf(primary, fallback)) }
                verifySuspend { serverConfig.setServerUrl(ServerUrl(fallback)) }
            }
        }

        test("ServerSelected failure transitions to Error state") {
            runTest {
                val serverRepository: ServerRepository = mock()
                val serverConfig: ServerConfig = mock()
                val instanceRepository: InstanceRepository = mock()
                val server = createServer()
                every { serverRepository.observeServers() } returns MutableStateFlow(emptyList())
                every { serverRepository.startDiscovery() } returns Unit
                everySuspend { instanceRepository.findReachableUrl(any()) } throws RuntimeException("Failed")

                val viewModel = ServerSelectViewModel(serverRepository, serverConfig, instanceRepository, errorBus = ErrorBus(), appScope = CoroutineScope(testDispatcher))
                keepStateHot(viewModel)
                viewModel.onEvent(ServerSelectUiEvent.LocalNetworkPermissionGranted)
                advanceUntilIdle()

                viewModel.onEvent(ServerSelectUiEvent.ServerSelected(createServerWithStatus(server)))
                advanceUntilIdle()

                val error = viewModel.state.value.shouldBeInstanceOf<ServerSelectUiState.Error>()
                error.selectedServerId shouldBe server.id
            }
        }

        test("ErrorDismissed transitions from Error back to Ready") {
            runTest {
                val serverRepository: ServerRepository = mock()
                val serverConfig: ServerConfig = mock()
                val instanceRepository: InstanceRepository = mock()
                val server = createServer()
                every { serverRepository.observeServers() } returns MutableStateFlow(emptyList())
                every { serverRepository.startDiscovery() } returns Unit
                everySuspend { instanceRepository.findReachableUrl(any()) } throws RuntimeException("Failed")

                val viewModel = ServerSelectViewModel(serverRepository, serverConfig, instanceRepository, errorBus = ErrorBus(), appScope = CoroutineScope(testDispatcher))
                keepStateHot(viewModel)
                viewModel.onEvent(ServerSelectUiEvent.LocalNetworkPermissionGranted)
                advanceUntilIdle()
                viewModel.onEvent(ServerSelectUiEvent.ServerSelected(createServerWithStatus(server)))
                advanceUntilIdle()
                viewModel.state.value.shouldBeInstanceOf<ServerSelectUiState.Error>()

                viewModel.onEvent(ServerSelectUiEvent.ErrorDismissed)
                advanceUntilIdle()

                viewModel.state.value.shouldBeInstanceOf<ServerSelectUiState.Ready>()
            }
        }
    })
