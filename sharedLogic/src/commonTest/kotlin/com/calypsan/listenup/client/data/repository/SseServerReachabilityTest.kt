package com.calypsan.listenup.client.data.repository

import app.cash.turbine.test
import com.calypsan.listenup.client.domain.model.ConnectionHealth
import com.calypsan.listenup.client.domain.repository.Reachability
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

/**
 * [SseServerReachability] projects the ONE connection-health source, so it can never disagree with
 * the shell banner about offline/healthy at the same instant (the former two-oracle split-brain).
 */
class SseServerReachabilityTest :
    FunSpec({
        test("Healthy and Outdated project to Reachable (server usable)") {
            runTest {
                val health = MutableStateFlow<ConnectionHealth>(ConnectionHealth.Healthy)
                val reachability = SseServerReachability(health, backgroundScope, reconnect = {})
                reachability.state.test {
                    var item = awaitItem()
                    while (item != Reachability.Reachable) item = awaitItem()
                    item shouldBe Reachability.Reachable
                    // Outdated is a non-blocking hint — still Reachable (no change surfaces).
                    health.value = ConnectionHealth.Outdated(clientVersion = "1.0", serverVersion = "2.0")
                    expectNoEvents()
                }
            }
        }

        test("Unreachable projects to Unreachable (book falls to download-only)") {
            runTest {
                val health = MutableStateFlow<ConnectionHealth>(ConnectionHealth.Unreachable(sinceMillis = 0L))
                val reachability = SseServerReachability(health, backgroundScope, reconnect = {})
                reachability.state.test {
                    var item = awaitItem()
                    while (item != Reachability.Unreachable) item = awaitItem()
                    item shouldBe Reachability.Unreachable
                }
            }
        }

        test("SessionExpired projects to Unreachable (server not usably reachable)") {
            runTest {
                val health = MutableStateFlow<ConnectionHealth>(ConnectionHealth.SessionExpired)
                val reachability = SseServerReachability(health, backgroundScope, reconnect = {})
                reachability.state.test {
                    var item = awaitItem()
                    while (item != Reachability.Unreachable) item = awaitItem()
                    item shouldBe Reachability.Unreachable
                }
            }
        }

        test("retry forces a reconnect") {
            runTest {
                val health = MutableStateFlow<ConnectionHealth>(ConnectionHealth.Healthy)
                var reconnectCalls = 0
                val reachability = SseServerReachability(health, backgroundScope, reconnect = { reconnectCalls++ })

                reachability.retry()

                reconnectCalls shouldBe 1
            }
        }
    })
