package com.calypsan.listenup.client.presentation.connect

import app.cash.turbine.test
import com.calypsan.listenup.api.error.UnexpectedClientError
import com.calypsan.listenup.api.error.ServerConnectError
import com.calypsan.listenup.client.domain.model.Server
import com.calypsan.listenup.client.domain.model.ServerWithStatus
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.domain.usecase.auth.AdoptServerUseCase
import com.calypsan.listenup.client.test.fake.FakeLocalNetworkAccess
import com.calypsan.listenup.client.test.fake.FakeServerRepository
import com.calypsan.listenup.core.ServerUrl
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.answering.throws
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
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

        class Fixture(
            val serverRepository: FakeServerRepository = FakeServerRepository(),
        ) {
            val serverConfig: ServerConfig = mock()
            val instanceRepository: InstanceRepository = mock()
            val localNetworkAccess = FakeLocalNetworkAccess()
            val errorBus = ErrorBus()

            fun build(): ServerSelectViewModel =
                ServerSelectViewModel(
                    serverRepository = serverRepository,
                    adoptServer = AdoptServerUseCase(serverConfig) {},
                    instanceRepository = instanceRepository,
                    localNetworkAccess = localNetworkAccess,
                    errorBus = errorBus,
                    appScope = CoroutineScope(testDispatcher),
                )

            fun stubAdoption() {
                everySuspend { serverConfig.setServerUrl(any()) } returns Unit
                everySuspend { serverConfig.getConnectedServerId() } returns null
                everySuspend { serverConfig.getLibraryServerId() } returns null
                everySuspend { serverConfig.setLibraryServerId(any()) } returns Unit
                everySuspend { serverConfig.setConnectedServerId(any()) } returns Unit
            }
        }

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
                val fixture = Fixture(FakeServerRepository(serverFlow = flow { /* never emits */ }))
                val viewModel = fixture.build()
                keepStateHot(viewModel)
                advanceUntilIdle()

                val discovering = viewModel.state.value.shouldBeInstanceOf<ServerSelectUiState.Discovering>()
                discovering.servers shouldBe emptyList()
            }
        }

        test("close stops mDNS discovery and is idempotent (#1192 iOS teardown)") {
            runTest {
                val fixture = Fixture()
                val viewModel = fixture.build()
                keepStateHot(viewModel)
                advanceUntilIdle()

                // iOS has no ViewModelStore; ServerSelectViewModelWrapper's isolated deinit calls
                // close() so the mDNS discovery doesn't announce/scan forever after the screen goes.
                viewModel.close()
                viewModel.close() // idempotent — the second call must not stop discovery again

                fixture.serverRepository.stopCount shouldBe 1
            }
        }

        test("LocalNetworkPermissionGranted starts server discovery") {
            runTest {
                val fixture = Fixture()
                val viewModel = fixture.build()
                keepStateHot(viewModel)

                viewModel.onEvent(ServerSelectUiEvent.LocalNetworkPermissionGranted)
                advanceUntilIdle()

                fixture.serverRepository.startCount shouldBe 1
            }
        }

        test("LocalNetworkPermissionGranted then observeServers emission transitions Discovering to Ready") {
            runTest {
                val fixture = Fixture()
                val viewModel = fixture.build()
                keepStateHot(viewModel)

                viewModel.onEvent(ServerSelectUiEvent.LocalNetworkPermissionGranted)
                advanceUntilIdle()

                // Initial emission (empty) flips to Ready
                viewModel.state.value.shouldBeInstanceOf<ServerSelectUiState.Ready>()

                fixture.serverRepository.servers.value = listOf(createServerWithStatus())
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<ServerSelectUiState.Ready>()
                ready.servers.size shouldBe 1
            }
        }

        test("LocalNetworkPermissionDenied navigates to manual entry and leaves the explaining to that screen") {
            runTest {
                val fixture = Fixture()
                val viewModel = fixture.build()
                keepStateHot(viewModel)
                advanceUntilIdle()

                // Before sign-in nothing collects the ErrorBus, so an emit here was dropped on the
                // floor. Manual entry now shows the permission card itself.
                fixture.errorBus.errors.test {
                    viewModel.onEvent(ServerSelectUiEvent.LocalNetworkPermissionDenied)
                    advanceUntilIdle()
                    expectNoEvents()
                }

                viewModel.navigationEvents.test {
                    awaitItem() shouldBe ServerSelectViewModel.NavigationEvent.GoToManualEntry
                }

                // Discovery must never start on the denial path.
                fixture.serverRepository.startCount shouldBe 0
            }
        }

        test("a browse the platform refuses surfaces LocalNetworkPermissionDenied for discovery itself") {
            runTest {
                val fixture = Fixture()
                val viewModel = fixture.build()
                keepStateHot(viewModel)
                viewModel.onEvent(ServerSelectUiEvent.LocalNetworkPermissionGranted)
                advanceUntilIdle()

                // iOS Bonjour answers a denied browse with kDNSServiceErr_PolicyDenied.
                fixture.serverRepository.localNetworkDenied.value = true
                advanceUntilIdle()

                val error = viewModel.state.value.shouldBeInstanceOf<ServerSelectUiState.Error>()
                error.selectedServerId shouldBe null
                error.error.shouldBeInstanceOf<ServerConnectError.LocalNetworkPermissionDenied>()
            }
        }

        test("LocalNetworkPermissionGranted after a refused browse restarts discovery and clears the error") {
            runTest {
                val fixture = Fixture()
                val viewModel = fixture.build()
                keepStateHot(viewModel)
                viewModel.onEvent(ServerSelectUiEvent.LocalNetworkPermissionGranted)
                advanceUntilIdle()
                fixture.serverRepository.localNetworkDenied.value = true
                advanceUntilIdle()
                viewModel.state.value.shouldBeInstanceOf<ServerSelectUiState.Error>()

                // A fresh browse resets the platform's verdict.
                fixture.serverRepository.localNetworkDenied.value = false
                viewModel.onEvent(ServerSelectUiEvent.LocalNetworkPermissionGranted)
                advanceUntilIdle()

                fixture.serverRepository.startCount shouldBe 2
                viewModel.state.value.shouldBeInstanceOf<ServerSelectUiState.Ready>()
            }
        }

        test("ManualEntryClicked emits GoToManualEntry navigation event") {
            runTest {
                val fixture = Fixture()
                val viewModel = fixture.build()
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
                val fixture = Fixture()
                val viewModel = fixture.build()
                keepStateHot(viewModel)
                viewModel.onEvent(ServerSelectUiEvent.LocalNetworkPermissionGranted)
                advanceUntilIdle()

                viewModel.onEvent(ServerSelectUiEvent.RefreshClicked)
                advanceUntilIdle()

                fixture.serverRepository.stopCount shouldBe 1
                fixture.serverRepository.startCount shouldBe 2
            }
        }

        test("ServerSelected activates server and emits navigation") {
            runTest {
                val fixture = Fixture()
                val server = createServer()
                everySuspend { fixture.instanceRepository.findReachableUrl(any()) } returns server.localUrl
                fixture.stubAdoption()

                val viewModel = fixture.build()
                keepStateHot(viewModel)
                viewModel.onEvent(ServerSelectUiEvent.LocalNetworkPermissionGranted)
                advanceUntilIdle()

                viewModel.navigationEvents.test {
                    viewModel.onEvent(ServerSelectUiEvent.ServerSelected(createServerWithStatus(server)))
                    advanceUntilIdle()

                    verifySuspend { fixture.serverConfig.setServerUrl(ServerUrl(server.localUrl!!)) }
                    verifySuspend { fixture.serverConfig.setConnectedServerId(server.id) }
                    awaitItem() shouldBe ServerSelectViewModel.NavigationEvent.ServerActivated
                }

                // After success, overlay cleared → Ready
                viewModel.state.value.shouldBeInstanceOf<ServerSelectUiState.Ready>()
            }
        }

        test("ServerSelected tries every resolved local URL and activates the reachable fallback") {
            runTest {
                val fixture = Fixture()
                val primary = "http://192.168.86.39:8080"
                val fallback = "http://192.168.86.37:8080"
                val server = createServer(localUrl = primary).copy(localUrls = listOf(primary, fallback))
                everySuspend { fixture.instanceRepository.findReachableUrl(any()) } returns fallback
                fixture.stubAdoption()

                val viewModel = fixture.build()
                keepStateHot(viewModel)
                viewModel.onEvent(ServerSelectUiEvent.LocalNetworkPermissionGranted)
                advanceUntilIdle()

                viewModel.onEvent(ServerSelectUiEvent.ServerSelected(createServerWithStatus(server)))
                advanceUntilIdle()

                // The whole candidate list (best-first) reaches reachability, and the reachable
                // fallback — not the unreachable primary — becomes the active URL.
                verifySuspend { fixture.instanceRepository.findReachableUrl(listOf(primary, fallback)) }
                verifySuspend { fixture.serverConfig.setServerUrl(ServerUrl(fallback)) }
            }
        }

        test("an activation that throws carries the mapped AppError, not pre-rendered copy") {
            runTest {
                val fixture = Fixture()
                val server = createServer()
                everySuspend { fixture.instanceRepository.findReachableUrl(any()) } throws RuntimeException("Failed")

                val viewModel = fixture.build()
                keepStateHot(viewModel)
                viewModel.onEvent(ServerSelectUiEvent.LocalNetworkPermissionGranted)
                advanceUntilIdle()

                viewModel.onEvent(ServerSelectUiEvent.ServerSelected(createServerWithStatus(server)))
                advanceUntilIdle()

                val error = viewModel.state.value.shouldBeInstanceOf<ServerSelectUiState.Error>()
                error.selectedServerId shouldBe server.id
                // ErrorMapper's verdict for an unclassified throwable — never "Failed to connect: …".
                error.error.shouldBeInstanceOf<UnexpectedClientError>()
            }
        }

        test("a discovered server that answers at no address is ServerNotReachable") {
            runTest {
                val fixture = Fixture()
                val server = createServer()
                everySuspend { fixture.instanceRepository.findReachableUrl(any()) } returns null

                val viewModel = fixture.build()
                keepStateHot(viewModel)
                viewModel.onEvent(ServerSelectUiEvent.LocalNetworkPermissionGranted)
                advanceUntilIdle()

                viewModel.onEvent(ServerSelectUiEvent.ServerSelected(createServerWithStatus(server)))
                advanceUntilIdle()

                val error = viewModel.state.value.shouldBeInstanceOf<ServerSelectUiState.Error>()
                error.error.shouldBeInstanceOf<ServerConnectError.ServerNotReachable>()
                fixture.localNetworkAccess.queries shouldBe listOf("192.168.1.100" to 8080)
            }
        }

        test("a discovered server the local-network gate blocks is LocalNetworkPermissionDenied") {
            runTest {
                val fixture = Fixture()
                fixture.localNetworkAccess.denied = true
                val server = createServer()
                everySuspend { fixture.instanceRepository.findReachableUrl(any()) } returns null

                val viewModel = fixture.build()
                keepStateHot(viewModel)
                viewModel.onEvent(ServerSelectUiEvent.LocalNetworkPermissionGranted)
                advanceUntilIdle()

                viewModel.onEvent(ServerSelectUiEvent.ServerSelected(createServerWithStatus(server)))
                advanceUntilIdle()

                val error = viewModel.state.value.shouldBeInstanceOf<ServerSelectUiState.Error>()
                error.selectedServerId shouldBe server.id
                error.error.shouldBeInstanceOf<ServerConnectError.LocalNetworkPermissionDenied>()
            }
        }

        test("a grant after a blocked activation re-runs that activation") {
            runTest {
                val fixture = Fixture()
                fixture.localNetworkAccess.denied = true
                val server = createServer()
                var reachable: String? = null
                everySuspend { fixture.instanceRepository.findReachableUrl(any()) } calls { reachable }
                fixture.stubAdoption()

                val viewModel = fixture.build()
                keepStateHot(viewModel)
                viewModel.onEvent(ServerSelectUiEvent.LocalNetworkPermissionGranted)
                advanceUntilIdle()
                viewModel.onEvent(ServerSelectUiEvent.ServerSelected(createServerWithStatus(server)))
                advanceUntilIdle()
                viewModel.state.value.shouldBeInstanceOf<ServerSelectUiState.Error>()

                fixture.localNetworkAccess.denied = false
                reachable = server.localUrl
                viewModel.navigationEvents.test {
                    viewModel.onEvent(ServerSelectUiEvent.LocalNetworkPermissionGranted)
                    advanceUntilIdle()
                    awaitItem() shouldBe ServerSelectViewModel.NavigationEvent.ServerActivated
                }
            }
        }

        // A blocked activation is re-run on a grant only while it is still the thing on screen.
        // Once the user has moved on — rescanned, dismissed it, or tapped another server — a later
        // grant must not activate a server they never tapped again.
        listOf(
            "a rescan" to { vm: ServerSelectViewModel, _: Fixture -> vm.onEvent(ServerSelectUiEvent.RefreshClicked) },
            "dismissing the error" to { vm: ServerSelectViewModel, _: Fixture -> vm.onEvent(ServerSelectUiEvent.ErrorDismissed) },
            "tapping another server that fails" to { vm: ServerSelectViewModel, f: Fixture ->
                f.localNetworkAccess.denied = false
                vm.onEvent(ServerSelectUiEvent.ServerSelected(createServerWithStatus(createServer(id = "server-2"))))
            },
        ).forEach { (movedOn, moveOn) ->
            test("after $movedOn, a grant does not activate the server the permission once blocked") {
                runTest {
                    val fixture = Fixture()
                    fixture.localNetworkAccess.denied = true
                    val serverA = createServer()
                    var reachable: String? = null
                    everySuspend { fixture.instanceRepository.findReachableUrl(any()) } calls { reachable }
                    fixture.stubAdoption()

                    val viewModel = fixture.build()
                    keepStateHot(viewModel)
                    viewModel.onEvent(ServerSelectUiEvent.LocalNetworkPermissionGranted)
                    advanceUntilIdle()
                    viewModel.onEvent(ServerSelectUiEvent.ServerSelected(createServerWithStatus(serverA)))
                    advanceUntilIdle()

                    moveOn(viewModel, fixture)
                    advanceUntilIdle()

                    // The browse is refused, then access is granted: only discovery should restart.
                    fixture.serverRepository.localNetworkDenied.value = true
                    advanceUntilIdle()
                    fixture.serverRepository.localNetworkDenied.value = false
                    fixture.localNetworkAccess.denied = false
                    reachable = serverA.localUrl
                    viewModel.navigationEvents.test {
                        viewModel.onEvent(ServerSelectUiEvent.LocalNetworkPermissionGranted)
                        advanceUntilIdle()
                        expectNoEvents()
                    }
                    verifySuspend(VerifyMode.not) { fixture.serverConfig.setServerUrl(any()) }
                }
            }
        }

        test("ErrorDismissed transitions from Error back to Ready") {
            runTest {
                val fixture = Fixture()
                val server = createServer()
                everySuspend { fixture.instanceRepository.findReachableUrl(any()) } throws RuntimeException("Failed")

                val viewModel = fixture.build()
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
