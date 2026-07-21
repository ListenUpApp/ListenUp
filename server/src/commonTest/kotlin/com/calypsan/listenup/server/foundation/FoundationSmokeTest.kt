package com.calypsan.listenup.server.foundation

import com.calypsan.listenup.api.PingService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.auth.JwtConfiguration
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.client.rpcConfig
import kotlinx.rpc.krpc.ktor.server.rpc
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.registerService
import kotlinx.rpc.withService

/**
 * Foundation smoke: the three-transport proof that the native HTTP skeleton actually *serves* —
 * REST, JWT auth, and kotlinx.rpc. As `commonTest` it runs on JVM **and** linuxX64; the native run
 * is the load-bearing "serves native" evidence (not just "compiles native").
 *
 * Uses `kotlin.test.@Test` + `runBlocking`, not Kotest FunSpec: Kotest 6.x dropped the multiplatform
 * Gradle plugin that generates native test entry points, so FunSpec specs are invisible to the K/N
 * runner. Kotest's assertions still compile and run on linuxX64, so they're kept.
 *
 * REST / auth go through `testApplication`. RPC goes through a real `embeddedServer(CIO)` on an
 * ephemeral port via [foundationServer], because the testApplication WebSocket bridge is unimplemented
 * on native and kotlinx.rpc rides WebSocket.
 */
class FoundationSmokeTest {
    // One JwtConfiguration shared by the server (via deps) and the token minting, so a minted token
    // verifies against the same secret. Liveness is always-true: a validly-signed token is accepted.
    private val jwt = JwtConfiguration("x".repeat(32), "listenup", "listenup-client")

    private fun deps(): FoundationDeps = FoundationDeps(jwt) { true }

    @Test
    fun restHealthzServesOk(): Unit =
        runBlocking {
            testApplication {
                application { installFoundation(deps()) }
                client.get("/healthz").bodyAsText().contains("ok") shouldBe true
            }
        }

    @Test
    fun whoamiRejectsAnonymousAndEchoesAuthedUser(): Unit =
        runBlocking {
            testApplication {
                application { installFoundation(deps()) }
                client.get("/healthz/whoami").status shouldBe HttpStatusCode.Unauthorized

                val token = jwt.issue(UserId("user-1"), SessionId("session-1"), UserRole.MEMBER)
                val authed = client.get("/healthz/whoami") { bearerAuth(token) }
                authed.status shouldBe HttpStatusCode.OK
                authed.bodyAsText() shouldBe "user-1"
            }
        }

    @Test
    fun rpcPingServesOverRealCioServer(): Unit =
        runBlocking {
            // installFoundation installs the Krpc transport but registers no service (guarded
            // registration is jvmMain-only today — see installFoundation's KDoc); the smoke
            // registers an unguarded test ping via the configure hook, in test scope.
            val server =
                foundationServer(port = 0, deps = deps()) {
                    routing {
                        rpc("/api/rpc/public") {
                            rpcConfig { serialization { json(contractJson) } }
                            registerService<PingService> { TestPing() }
                        }
                    }
                }
            server.start(wait = false)
            try {
                // Ephemeral port (0): resolvedConnectors() suspends until the socket is bound, so it
                // both yields the assigned port and removes any fixed-port collision / bind-race flake.
                val port =
                    server.engine
                        .resolvedConnectors()
                        .first()
                        .port
                val rpcClient =
                    HttpClient(ClientCIO) {
                        install(ClientWebSockets)
                        installKrpc()
                    }
                try {
                    val ping =
                        rpcClient
                            .rpc("ws://127.0.0.1:$port/api/rpc/public") {
                                rpcConfig { serialization { json(contractJson) } }
                            }.withService<PingService>()
                    (ping.ping() as AppResult.Success).data shouldBe "pong"
                } finally {
                    rpcClient.close()
                }
            } finally {
                server.stop(0, 0)
            }
        }

    /** Test-scope ping impl — unguarded by design (the rpc-guard decorator is jvmMain-only). */
    private class TestPing : PingService {
        override suspend fun ping(): AppResult<String> = AppResult.Success("pong")
    }
}
