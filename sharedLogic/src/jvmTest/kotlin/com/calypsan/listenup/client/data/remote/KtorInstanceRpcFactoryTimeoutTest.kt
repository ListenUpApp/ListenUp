package com.calypsan.listenup.client.data.remote

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket

/**
 * Regression test for the server-picker spinner hang: selecting a discovered server whose address
 * accepts a TCP connection but never completes the WebSocket upgrade must FAIL fast, not hang.
 *
 * Before [KtorInstanceRpcFactory] installed `HttpTimeout`, the probe's WebSocket client had no
 * connect/request bound, so `getServerInfo` against such a host blocked forever — `findReachableUrl`
 * never returned and the `Connecting` overlay never resolved.
 */
class KtorInstanceRpcFactoryTimeoutTest :
    FunSpec({
        test("getServerInfo against a black-hole host fails fast instead of hanging") {
            // Accept connections but never read/write — TCP connects, the WS upgrade never responds.
            val blackHole = ServerSocket(0)
            val acceptor =
                Thread {
                    runCatching {
                        while (!blackHole.isClosed) {
                            blackHole.accept() // leak the socket on purpose; never respond
                        }
                    }
                }.apply {
                    isDaemon = true
                    start()
                }

            try {
                val factory =
                    KtorInstanceRpcFactory(
                        connectTimeoutMillis = 500,
                        requestTimeoutMillis = 800,
                        socketTimeoutMillis = 800,
                    )

                // Kotest runs this block on a real-time dispatcher, so withTimeout is a real wall-clock
                // guard: if the internal timeout is missing the probe hangs and this 10s bound fails the
                // test; with it, the probe errors in well under a second.
                val result =
                    withTimeout(10_000) {
                        runCatching { factory.getServerInfo("ws://127.0.0.1:${blackHole.localPort}") }
                    }

                result.isFailure shouldBe true
            } finally {
                blackHole.close()
                acceptor.interrupt()
            }
        }
    })
