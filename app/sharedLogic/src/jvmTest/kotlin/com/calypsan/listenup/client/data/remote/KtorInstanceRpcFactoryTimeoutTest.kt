package com.calypsan.listenup.client.data.remote

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.withTimeoutOrNull
import java.net.ServerSocket

/**
 * Regression test for the server-picker spinner hang: selecting a discovered server whose address
 * accepts a TCP connection but never completes the WebSocket upgrade must FAIL fast, not hang.
 *
 * The probe's WebSocket client must bound the whole operation: the black-hole accepts the TCP
 * connection (so a connect-only timeout won't fire) but never sends the `101` upgrade response, the
 * shape that previously stalled `findReachableUrl` / `checkServerStatus` and left the `Connecting`
 * overlay spinning. `withTimeoutOrNull` inside the factory caps it; here we prove it always completes.
 */
class KtorInstanceRpcFactoryTimeoutTest :
    FunSpec({
        test("getServerInfo against a black-hole host completes fast instead of hanging") {
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

                // Kotest runs this block on a real-time dispatcher, so this is a real wall-clock guard:
                // if the internal bound is missing the probe hangs and this 10s timeout returns null,
                // failing the test. With it, getServerInfo returns (a Timeout Failure) or throws — either
                // way it COMPLETES, so the outer guard yields a non-null result.
                val completed =
                    withTimeoutOrNull(10_000) {
                        runCatching { factory.getServerInfo("ws://127.0.0.1:${blackHole.localPort}") }
                    }

                completed shouldNotBe null
            } finally {
                blackHole.close()
                acceptor.interrupt()
            }
        }
    })
