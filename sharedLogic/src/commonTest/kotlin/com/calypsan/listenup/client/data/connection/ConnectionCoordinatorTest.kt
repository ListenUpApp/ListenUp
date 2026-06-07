package com.calypsan.listenup.client.data.connection

import com.calypsan.listenup.client.data.remote.RpcCacheInvalidator
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.core.ServerUrl
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode.Companion.exactly
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
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
                ConnectionCoordinator(serverConfig, instance, networkMonitor, FakeInvalidator(), scope)

            scope.launch { coordinator.reevaluate() }
            scope.testScheduler.advanceUntilIdle()

            verifySuspend { serverConfig.setActiveUrl(ServerUrl(local)) }
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
                ConnectionCoordinator(serverConfig, instance, networkMonitor, FakeInvalidator(), scope)

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
                ConnectionCoordinator(serverConfig, instance, networkMonitor, FakeInvalidator(), scope)

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
            ConnectionCoordinator(serverConfig, instance, networkMonitor, invalidator, scope).start()
            scope.testScheduler.advanceUntilIdle()
            invalidator.count shouldBe 0

            active.value = ServerUrl(remote)
            scope.testScheduler.advanceUntilIdle()
            invalidator.count shouldBe 1
        }
    })
