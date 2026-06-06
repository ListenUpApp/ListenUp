package com.calypsan.listenup.client.data.repository

import app.cash.turbine.test
import com.calypsan.listenup.client.data.sync.ConnectionState
import com.calypsan.listenup.client.data.sync.SyncEngineState
import com.calypsan.listenup.client.domain.repository.Reachability
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class SseServerReachabilityTest :
    FunSpec({
        test("Connected maps to Reachable") {
            runTest {
                val engineState = SyncEngineState()
                val reachability = SseServerReachability(engineState, backgroundScope, reconnect = {})
                engineState.setConnection(ConnectionState.Connected(lastEventId = 1L))
                reachability.state.test {
                    var item = awaitItem()
                    while (item != Reachability.Reachable) item = awaitItem()
                    item shouldBe Reachability.Reachable
                }
            }
        }

        test("Disconnected maps to Unreachable; Connecting maps to Unknown") {
            runTest {
                val engineState = SyncEngineState()
                val reachability = SseServerReachability(engineState, backgroundScope, reconnect = {})
                engineState.setConnection(ConnectionState.Connecting)
                reachability.state.test {
                    var item = awaitItem()
                    while (item != Reachability.Unknown) item = awaitItem()
                    item shouldBe Reachability.Unknown
                }
                engineState.setConnection(ConnectionState.Disconnected("closed"))
                reachability.state.test {
                    var item = awaitItem()
                    while (item != Reachability.Unreachable) item = awaitItem()
                    item shouldBe Reachability.Unreachable
                }
            }
        }

        test("retry forces a reconnect") {
            runTest {
                val engineState = SyncEngineState()
                var reconnectCalls = 0
                val reachability =
                    SseServerReachability(engineState, backgroundScope, reconnect = { reconnectCalls++ })

                reachability.retry()

                reconnectCalls shouldBe 1
            }
        }
    })
