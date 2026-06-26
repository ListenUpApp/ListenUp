package com.calypsan.listenup.server.foundation

import com.calypsan.listenup.api.PingService
import com.calypsan.listenup.api.result.AppResult
import io.kotest.matchers.shouldBe
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.client.rpcConfig
import kotlinx.rpc.krpc.ktor.server.Krpc
import kotlinx.rpc.krpc.ktor.server.rpc
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.registerService
import kotlinx.rpc.withService

private class SpikePing : PingService {
    override suspend fun ping(): AppResult<String> = AppResult.Success("pong")
}

/**
 * Decision spike: proves that [testApplication] + kotlinx.rpc round-trips run on both JVM and
 * linuxX64. Uses `@Test` + `runBlocking` instead of Kotest FunSpec because Kotest 6.2.1 dropped
 * the Gradle multiplatform plugin that generates native entry points — FunSpec specs are invisible
 * to the K/N test runner without it. `@Test` is discovered natively. Kotest assertions are kept
 * since they compile and run fine on linuxX64.
 *
 * Pass on both targets → native test runtime confirmed.
 * Compile failure or runtime error on linuxX64Test → capture exact error and report.
 */
class NativeRuntimeSpikeTest {

    @Test
    fun restRouteSurvivesUnderTestApplication() = runBlocking {
        testApplication {
            routing { get("/healthz") { call.respondText("ok") } }
            client.get("/healthz").bodyAsText() shouldBe "ok"
        }
    }

    @Test
    fun rpcRoundTripSurvivesUnderTestApplication() = runBlocking {
        testApplication {
            application {
                install(ServerWebSockets)
                install(Krpc)
                routing {
                    rpc("/api/rpc") {
                        rpcConfig { serialization { json() } }
                        registerService<PingService> { SpikePing() }
                    }
                }
            }
            val rpcClient =
                createClient {
                    install(WebSockets)
                    installKrpc()
                }
            val ping =
                rpcClient
                    .rpc("ws://localhost/api/rpc") {
                        rpcConfig { serialization { json() } }
                    }.withService<PingService>()
            (ping.ping() as AppResult.Success).data shouldBe "pong"
        }
    }
}
