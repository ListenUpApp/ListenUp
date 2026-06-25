package com.calypsan.listenup.client.data.repository

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * Regression coverage for the health-check cancellation contract on [JvmNetworkMonitor].
 *
 * The polling loop runs suspending HTTP calls; the per-check `try`/`catch` must obey the
 * Error Model rubric — re-throw [CancellationException] so a cancelled poll propagates
 * instead of being misreported as "server unreachable". Other failures (timeouts, refused
 * connections) are the legitimate offline signal and stay swallowed.
 */
class JvmNetworkMonitorTest :
    FunSpec({

        test("checkHealth re-throws CancellationException instead of reporting offline") {
            val monitor =
                JvmNetworkMonitor(
                    serverUrlProvider = { "http://localhost:1" },
                    httpClient = HttpClient(MockEngine { throw CancellationException("poll cancelled mid-flight") }),
                )

            shouldThrow<CancellationException> { monitor.checkHealth() }
        }

        test("checkHealth swallows a genuine connection failure and reports offline") {
            val monitor =
                JvmNetworkMonitor(
                    serverUrlProvider = { "http://localhost:1" },
                    httpClient = HttpClient(MockEngine { throw IOException("connection refused") }),
                )

            monitor.checkHealth() // must NOT throw — a failed reachability check is normal
            monitor.isOnline() shouldBe false
        }
    })
