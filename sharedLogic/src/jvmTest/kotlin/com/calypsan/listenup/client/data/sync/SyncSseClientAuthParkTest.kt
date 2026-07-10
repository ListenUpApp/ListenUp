@file:OptIn(ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.client.data.sync

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

class SyncSseClientAuthParkTest :
    FunSpec({
        test("two consecutive 401 connects report auth exhaustion ONCE and park at the 5-minute heartbeat") {
            // The production streaming clients raise ResponseException on non-2xx (expectSuccess =
            // true via installListenUpErrorHandling). A real MockEngine round-trip does NOT settle
            // under the StandardTestDispatcher's virtual time (its response pipeline escapes to the
            // engine's own dispatcher — the reason SyncSseClientAuthRefreshTest runs on real
            // dispatchers + wall-clock timeouts). To keep the park/backoff ladder under deterministic
            // virtual-time control, capture ONE genuine 401 ResponseException, then have the provider
            // throw it synchronously — runOnce's `catch (e: ResponseException)` maps status 401 to
            // AuthFailed exactly as it would from a live connect (mirrors the reference reconnect
            // test's synchronous `error("boom")` fixture).
            val unauthorized401: ResponseException =
                runBlocking {
                    val probe =
                        HttpClient(MockEngine { respond("unauthorized", HttpStatusCode.Unauthorized) }) {
                            expectSuccess = true
                        }
                    try {
                        probe.get("http://test")
                        error("expected a 401 ResponseException")
                    } catch (e: ResponseException) {
                        e
                    } finally {
                        probe.close()
                    }
                }

            val scope = TestScope(StandardTestDispatcher())
            var attempts = 0
            var exhausted = 0
            val state = SyncEngineState()
            val client =
                SyncSseClient(
                    serverUrlProvider = { "http://test" },
                    streamingClientProvider = {
                        attempts++
                        throw unauthorized401 // → runOnce maps 401 → AuthFailed
                    },
                    state = state,
                    scope = scope,
                    nowMillis = { 0L },
                    onAuthExhausted = { exhausted++ },
                )

            client.connect()
            scope.testScheduler.runCurrent() // attempt 1 → 401 → streak 1, normal 1s backoff
            attempts shouldBe 1
            exhausted shouldBe 0

            scope.testScheduler.advanceTimeBy(1_001)
            scope.testScheduler.runCurrent() // attempt 2 → 401 → streak 2 → exhausted, parked
            attempts shouldBe 2
            exhausted shouldBe 1
            state.value.connection shouldBe ConnectionState.Disconnected("auth")

            // Parked: a window that previously produced a 2s→60s retry ladder now produces NOTHING
            // until the 5-minute heartbeat elapses (spam bound).
            scope.testScheduler.advanceTimeBy(299_000)
            scope.testScheduler.runCurrent()
            attempts shouldBe 2

            scope.testScheduler.advanceTimeBy(2_000)
            scope.testScheduler.runCurrent() // heartbeat fires exactly once
            attempts shouldBe 3
            exhausted shouldBe 1 // once per streak, not per parked attempt

            // Never stranded: reconnectNow wakes the park instantly and resets the streak.
            client.reconnectNow()
            scope.testScheduler.runCurrent()
            attempts shouldBe 4

            client.disconnect()
        }
    })
