package com.calypsan.listenup.client.data.connection

import com.calypsan.listenup.client.data.discovery.DiscoveredServer
import com.calypsan.listenup.client.data.discovery.ServerDiscoveryService
import com.calypsan.listenup.client.data.remote.RpcCacheInvalidator
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.core.ServerUrl
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify
import dev.mokkery.verify.VerifyMode.Companion.exactly
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle

class ConnectionCoordinatorTest :
    FunSpec({
        class FakeInvalidator : RpcCacheInvalidator {
            var count = 0

            override suspend fun invalidateAll() {
                count++
            }
        }

        val local = "http://192.168.1.10:8080"
        val remote = "https://remote.example.com"

        /** A no-op discovery mock — never finds anything, never expected to be driven. */
        fun idleDiscovery(): ServerDiscoveryService =
            mock {
                every { discover() } returns flowOf(emptyList())
                every { startDiscovery() } returns Unit
                every { stopDiscovery() } returns Unit
            }

        test("reevaluate switches to the reachable LAN url") {
            val scope = TestScope(StandardTestDispatcher())
            val online = MutableStateFlow(true)
            val serverConfig =
                mock<ServerConfig> {
                    every { activeUrl } returns MutableStateFlow<ServerUrl?>(null)
                    everySuspend { getActiveUrl() } returns ServerUrl(local)
                    everySuspend { getServerUrl() } returns ServerUrl(local)
                    everySuspend { getRemoteUrl() } returns ServerUrl(remote)
                    everySuspend { setActiveUrl(any()) } returns Unit
                }
            val instance =
                mock<InstanceRepository> {
                    everySuspend { findReachableUrl(any()) } returns local
                }
            val networkMonitor =
                mock<NetworkMonitor> {
                    every { isOnlineFlow } returns online
                    every { isOnline() } returns online.value
                }
            val coordinator =
                ConnectionCoordinator(serverConfig, instance, idleDiscovery(), networkMonitor, FakeInvalidator(), scope)

            scope.launch { coordinator.reevaluate() }
            scope.testScheduler.advanceUntilIdle()

            verifySuspend { serverConfig.setActiveUrl(ServerUrl(local)) }
        }

        test("reevaluate prefers reachable LAN, no discovery") {
            val scope = TestScope(StandardTestDispatcher())
            val discoveryService = idleDiscovery()
            val serverConfig =
                mock<ServerConfig> {
                    every { activeUrl } returns MutableStateFlow<ServerUrl?>(null)
                    everySuspend { getServerUrl() } returns ServerUrl(local)
                    everySuspend { getRemoteUrl() } returns ServerUrl(remote)
                    everySuspend { setActiveUrl(any()) } returns Unit
                }
            val instance =
                mock<InstanceRepository> {
                    everySuspend { findReachableUrl(listOf(local)) } returns local
                }
            val networkMonitor =
                mock<NetworkMonitor> {
                    every { isOnlineFlow } returns MutableStateFlow(true)
                    every { isOnline() } returns true
                }
            val coordinator =
                ConnectionCoordinator(serverConfig, instance, discoveryService, networkMonitor, FakeInvalidator(), scope)

            scope.launch { coordinator.reevaluate() }
            scope.testScheduler.advanceUntilIdle()

            verifySuspend { serverConfig.setActiveUrl(ServerUrl(local)) }
            verify(exactly(0)) { discoveryService.startDiscovery() }
        }

        test("reevaluate relocates via discovery when LAN unreachable") {
            val scope = TestScope(StandardTestDispatcher())
            val staleLocal = "http://old:8080"
            val newLocal = "http://192.168.1.20:8080"
            val discoveryService =
                mock<ServerDiscoveryService> {
                    every { discover() } returns
                        flowOf(
                            listOf(
                                DiscoveredServer(
                                    id = "abc",
                                    name = "s",
                                    host = "192.168.1.20",
                                    port = 8080,
                                    apiVersion = "v1",
                                    serverVersion = "1",
                                ),
                            ),
                        )
                    every { startDiscovery() } returns Unit
                    every { stopDiscovery() } returns Unit
                }
            val serverConfig =
                mock<ServerConfig> {
                    every { activeUrl } returns MutableStateFlow<ServerUrl?>(null)
                    everySuspend { getServerUrl() } returns ServerUrl(staleLocal)
                    everySuspend { getRemoteUrl() } returns ServerUrl(remote)
                    everySuspend { getConnectedServerId() } returns "abc"
                    everySuspend { updateLocalUrl(any()) } returns Unit
                    everySuspend { setActiveUrl(any()) } returns Unit
                }
            val instance =
                mock<InstanceRepository> {
                    everySuspend { findReachableUrl(any()) } calls { (urls: List<String>) ->
                        if (urls == listOf(newLocal)) newLocal else null
                    }
                }
            val networkMonitor =
                mock<NetworkMonitor> {
                    every { isOnlineFlow } returns MutableStateFlow(true)
                    every { isOnline() } returns true
                }
            val coordinator =
                ConnectionCoordinator(serverConfig, instance, discoveryService, networkMonitor, FakeInvalidator(), scope)

            scope.launch { coordinator.reevaluate() }
            scope.testScheduler.advanceUntilIdle()

            verifySuspend { serverConfig.updateLocalUrl(ServerUrl(newLocal)) }
            verifySuspend { serverConfig.setActiveUrl(ServerUrl(newLocal)) }
            verify { discoveryService.startDiscovery() }
            verify { discoveryService.stopDiscovery() }
        }

        test("reevaluate falls back to remote when LAN dead and no anchor") {
            val scope = TestScope(StandardTestDispatcher())
            val staleLocal = "http://old:8080"
            val discoveryService = idleDiscovery()
            val serverConfig =
                mock<ServerConfig> {
                    every { activeUrl } returns MutableStateFlow<ServerUrl?>(null)
                    everySuspend { getServerUrl() } returns ServerUrl(staleLocal)
                    everySuspend { getRemoteUrl() } returns ServerUrl(remote)
                    everySuspend { getConnectedServerId() } returns null
                    everySuspend { setActiveUrl(any()) } returns Unit
                }
            val instance =
                mock<InstanceRepository> {
                    everySuspend { findReachableUrl(any()) } calls { (urls: List<String>) ->
                        if (urls == listOf(remote)) remote else null
                    }
                }
            val networkMonitor =
                mock<NetworkMonitor> {
                    every { isOnlineFlow } returns MutableStateFlow(true)
                    every { isOnline() } returns true
                }
            val coordinator =
                ConnectionCoordinator(serverConfig, instance, discoveryService, networkMonitor, FakeInvalidator(), scope)

            scope.launch { coordinator.reevaluate() }
            scope.testScheduler.advanceUntilIdle()

            verifySuspend { serverConfig.setActiveUrl(ServerUrl(remote)) }
        }

        test("reevaluate keeps current when nothing reachable") {
            val scope = TestScope(StandardTestDispatcher())
            val online = MutableStateFlow(true)
            val serverConfig =
                mock<ServerConfig> {
                    every { activeUrl } returns MutableStateFlow<ServerUrl?>(null)
                    everySuspend { getActiveUrl() } returns ServerUrl(local)
                    everySuspend { getServerUrl() } returns ServerUrl(local)
                    everySuspend { getRemoteUrl() } returns ServerUrl(remote)
                    everySuspend { getConnectedServerId() } returns null
                    everySuspend { setActiveUrl(any()) } returns Unit
                }
            val instance =
                mock<InstanceRepository> {
                    everySuspend { findReachableUrl(any()) } returns null
                }
            val networkMonitor =
                mock<NetworkMonitor> {
                    every { isOnlineFlow } returns online
                    every { isOnline() } returns online.value
                }
            val coordinator =
                ConnectionCoordinator(serverConfig, instance, idleDiscovery(), networkMonitor, FakeInvalidator(), scope)

            scope.launch { coordinator.reevaluate() }
            scope.testScheduler.advanceUntilIdle()

            verifySuspend(exactly(0)) { serverConfig.setActiveUrl(any()) }
        }

        test("network regain false->true triggers reevaluate") {
            val scope = TestScope(StandardTestDispatcher())
            val online = MutableStateFlow(false)
            val serverConfig =
                mock<ServerConfig> {
                    every { activeUrl } returns MutableStateFlow<ServerUrl?>(null)
                    everySuspend { getActiveUrl() } returns ServerUrl(local)
                    everySuspend { getServerUrl() } returns ServerUrl(local)
                    everySuspend { getRemoteUrl() } returns ServerUrl(remote)
                    everySuspend { setActiveUrl(any()) } returns Unit
                }
            val instance =
                mock<InstanceRepository> {
                    everySuspend { findReachableUrl(any()) } returns local
                }
            val networkMonitor =
                mock<NetworkMonitor> {
                    every { isOnlineFlow } returns online
                    every { isOnline() } returns online.value
                }
            val coordinator =
                ConnectionCoordinator(serverConfig, instance, idleDiscovery(), networkMonitor, FakeInvalidator(), scope)

            coordinator.start()
            scope.testScheduler.advanceUntilIdle()

            online.value = true
            scope.testScheduler.advanceUntilIdle()

            verifySuspend { instance.findReachableUrl(any()) }
        }

        test("invalidates when the active URL host changes") {
            val scope = TestScope(StandardTestDispatcher())
            val active = MutableStateFlow<ServerUrl?>(ServerUrl(local))
            val online = MutableStateFlow(true)
            val invalidator = FakeInvalidator()
            val serverConfig =
                mock<ServerConfig> {
                    every { activeUrl } returns active
                    everySuspend { getActiveUrl() } returns ServerUrl(local)
                    everySuspend { getServerUrl() } returns ServerUrl(local)
                    everySuspend { getRemoteUrl() } returns ServerUrl(remote)
                    everySuspend { setActiveUrl(any()) } returns Unit
                }
            val instance =
                mock<InstanceRepository> {
                    everySuspend { findReachableUrl(any()) } returns local
                }
            val networkMonitor =
                mock<NetworkMonitor> {
                    every { isOnlineFlow } returns online
                    every { isOnline() } returns online.value
                }
            ConnectionCoordinator(serverConfig, instance, idleDiscovery(), networkMonitor, invalidator, scope).start()
            scope.testScheduler.advanceUntilIdle()
            invalidator.count shouldBe 0

            active.value = ServerUrl(remote)
            scope.testScheduler.advanceUntilIdle()
            invalidator.count shouldBe 1
        }
    })
