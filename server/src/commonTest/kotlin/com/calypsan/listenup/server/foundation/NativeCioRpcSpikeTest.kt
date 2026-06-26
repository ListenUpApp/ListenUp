package com.calypsan.listenup.server.foundation

import com.calypsan.listenup.api.PingService
import com.calypsan.listenup.api.result.AppResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.server.application.install
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.client.rpcConfig
import kotlinx.rpc.krpc.ktor.server.Krpc
import kotlinx.rpc.krpc.ktor.server.rpc
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.registerService
import kotlinx.rpc.withService

private class CioSpikePing : PingService {
    override suspend fun ping(): AppResult<String> = AppResult.Success("pong")
}

/**
 * Decision spike: proves that a REAL [embeddedServer] (CIO engine, no test-host shim) can
 * serve kotlinx.rpc over WebSocket on both JVM and linuxX64.
 *
 * This is the load-bearing question for the Kotlin Native server port.  The prior spike
 * ([NativeRuntimeSpikeTest]) showed that the testApplication WS bridge throws
 * `kotlin.NotImplementedError` on native — that's a test-client limitation, not an engine
 * limitation.  Here we bypass the test host entirely:
 *   - Server: real `embeddedServer(CIO, port)` with Krpc + PingService registered.
 *   - Client: real `HttpClient(CIO)` with WebSockets + installKrpc() connecting to the live port.
 *
 * PASS on linuxX64Test → CIO server serves kRPC over WS on native.  Green light for the
 * foundation.
 * FAIL → capture the exact error and layer (server-start / WS-handshake / RPC-call).
 */
class NativeCioRpcSpikeTest {

    @Test
    fun realCioServerServesRpcOverWebSocket() = runBlocking {
        val port = 8099
        val server = embeddedServer(ServerCIO, port = port) {
            install(ServerWebSockets)
            install(Krpc)
            routing {
                rpc("/api/rpc") {
                    rpcConfig { serialization { json() } }
                    registerService<PingService> { CioSpikePing() }
                }
            }
        }
        server.start(wait = false)
        // Brief pause to let the CIO engine bind the socket before the client connects.
        delay(200)
        try {
            val client = HttpClient(ClientCIO) {
                install(ClientWebSockets)
                installKrpc()
            }
            try {
                val ping =
                    client
                        .rpc("ws://127.0.0.1:$port/api/rpc") {
                            rpcConfig { serialization { json() } }
                        }.withService<PingService>()
                val result = ping.ping()
                assertEquals("pong", (result as AppResult.Success).data)
            } finally {
                client.close()
            }
        } finally {
            server.stop(0, 0)
        }
    }
}
