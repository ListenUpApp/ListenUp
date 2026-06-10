@file:OptIn(ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.client.data.sync

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

class SyncSseClientReconnectNowTest :
    FunSpec({
        test("reconnectNow wakes the backoff wait and resets the delay") {
            val scope = TestScope(StandardTestDispatcher())
            var attempts = 0
            val state = SyncEngineState()
            val client =
                SyncSseClient(
                    serverUrlProvider = { "http://test" },
                    streamingClientProvider = {
                        attempts++
                        error("boom") // always fail → drives the Reconnect backoff path
                    },
                    state = state,
                    scope = scope,
                    nowMillis = { 0L },
                )

            client.connect()
            scope.testScheduler.runCurrent()
            val afterFirst = attempts
            afterFirst shouldBeGreaterThan 0

            scope.testScheduler.advanceTimeBy(500)
            scope.testScheduler.runCurrent()
            attempts shouldBe afterFirst // still waiting out the backoff

            client.reconnectNow()
            scope.testScheduler.runCurrent()
            attempts shouldBe afterFirst + 1 // woke immediately, exactly one more attempt (no busy-loop)

            client.disconnect()
        }
    })
