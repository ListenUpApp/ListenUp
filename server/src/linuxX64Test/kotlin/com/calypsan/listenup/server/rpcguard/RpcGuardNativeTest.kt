package com.calypsan.listenup.server.rpcguard

import com.calypsan.listenup.api.PingService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.foundation.FoundationDeps
import com.calypsan.listenup.server.foundation.foundationServer
import com.calypsan.listenup.server.auth.JwtConfiguration
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.client.rpcConfig
import kotlinx.rpc.krpc.ktor.server.rpc
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.registerService
import kotlinx.rpc.withService
import io.ktor.server.routing.routing

/**
 * P5-1 native proof: a generated `PingServiceGuarded` wrapping a throwing impl must convert the
 * escaped exception into `AppResult.Failure(InternalError(...))` with a correlation id and the
 * constant user-facing message (no stacktrace can ride InternalError) — running on linuxX64 over
 * the real CIO/krpc transport.
 *
 * `kotlin.test.@Test` + `runBlocking` (Kotest's FunSpec is invisible to the K/N test runner;
 * its assertions still run). Lives in `linuxX64Test` because `PingServiceGuarded` is generated
 * into `:contract`'s linuxX64 source set, not commonMain.
 */
class RpcGuardNativeTest {
    private val jwt = JwtConfiguration("x".repeat(32), "listenup", "listenup-client")

    private class ThrowingPing : PingService {
        override suspend fun ping(): AppResult<String> = throw RuntimeException("boom")
    }

    @Test
    fun guardSanitizesThrownExceptionOnNative(): Unit =
        runBlocking {
            val server =
                foundationServer(port = 0, deps = FoundationDeps(jwt) { true }) {
                    routing {
                        rpc("/api/rpc/public") {
                            rpcConfig { serialization { json(contractJson) } }
                            registerService<PingService> { PingServiceGuarded(ThrowingPing()) }
                        }
                    }
                }
            server.start(wait = false)
            try {
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
                    val result = ping.ping()
                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    val error = failure.error.shouldBeInstanceOf<InternalError>()
                    error.correlationId.shouldNotBeNull()
                    error.message shouldBe "Something went wrong on the server."
                    error.code shouldBe "INTERNAL_ERROR"
                    // Post-err#3: the wire InternalError carries ONLY the correlation id — the server
                    // exception's class name + message never ride across (no `cause`, no `debugInfo`).
                    error.cause shouldBe null
                    error.debugInfo shouldBe null
                } finally {
                    rpcClient.close()
                }
            } finally {
                server.stop(0, 0)
            }
        }
}
