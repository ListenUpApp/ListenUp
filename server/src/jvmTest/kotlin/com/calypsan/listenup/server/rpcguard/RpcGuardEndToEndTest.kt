package com.calypsan.listenup.server.rpcguard

import com.calypsan.listenup.api.AuthServicePublic
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RefreshRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.result.AppResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.client.rpcConfig
import kotlinx.rpc.krpc.ktor.server.Krpc
import kotlinx.rpc.krpc.ktor.server.rpc
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.registerService
import kotlinx.rpc.withService

/**
 * End-to-end proof that the RPC exception guard works as an integrated unit:
 * a real [testApplication] boots with a deliberately broken [AuthServicePublic]
 * wired through the production [guard] machinery, a real kotlinx.rpc client
 * connects via WebSocket, and the test asserts that the client receives a typed
 * [InternalError] — never a raw stacktrace.
 *
 * This is the load-bearing security test for the RPC exception guard.
 * If it passes, stacktraces structurally cannot cross the wire for any service
 * wrapped by [guard].
 */
class RpcGuardEndToEndTest :
    FunSpec({

        test("escaped exception arrives at client as InternalError with no stacktrace on the wire") {
            testApplication {
                application {
                    // Minimal bootstrap: just enough for the RPC transport to
                    // function. No Koin, no JWT auth, no DB — this test only
                    // cares about the guard layer, not auth or persistence.
                    install(ServerWebSockets)
                    install(Krpc)

                    routing {
                        rpc("/api/rpc/public") {
                            rpcConfig { serialization { json(contractJson) } }
                            registerService<AuthServicePublic> { guard(BrokenAuthService()) }
                        }
                    }
                }

                val rpcClient =
                    createClient {
                        install(WebSockets)
                        installKrpc()
                    }

                val service =
                    rpcClient
                        .rpc("ws://localhost/api/rpc/public") {
                            rpcConfig { serialization { json(contractJson) } }
                        }.withService<AuthServicePublic>()

                val result =
                    service.login(
                        LoginRequest(email = "u@example.com", password = "password123"),
                    )

                // Assert: typed Failure with InternalError — not a raw exception.
                val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                val internalError = failure.error.shouldBeInstanceOf<InternalError>()
                // The server exception's class name and message must NOT cross the wire — they can
                // embed SQL / paths / hostnames. The full detail stays in the server log, keyed by cid.
                internalError.cause shouldBe null
                internalError.debugInfo shouldBe null
                // correlationId must be UUID-shaped (36 chars with hyphens).
                val cid = internalError.correlationId ?: ""
                cid.length shouldBe 36

                // Critical invariant: the serialized wire payload leaks no server-internal detail —
                // no exception class name, no message, and no stacktrace markers (" at " frame
                // separators or "java.base" JDK module paths).
                val encoded = contractJson.encodeToString(AppError.serializer(), internalError)
                encoded.shouldNotContain(" at ")
                encoded.shouldNotContain("java.base")
                encoded.shouldNotContain("NullPointerException")
                encoded.shouldNotContain("e2e-test")
            }
        }
    })

/**
 * A deliberately broken [AuthServicePublic] whose [login] always throws
 * [NullPointerException]. This simulates a server-side bug that escapes the
 * domain layer and must be caught by the [guard] decorator before crossing
 * the wire.
 *
 * Only [login] is wired — the test only calls that method. All other methods
 * throw to catch accidental calls.
 */
private class BrokenAuthService : AuthServicePublic {
    override suspend fun login(request: LoginRequest): AppResult<AuthSession> = throw NullPointerException("e2e-test: forced escape")

    override suspend fun register(request: RegisterRequest): AppResult<RegisterResult> = error("not used in this test")

    override suspend fun setupRoot(request: RegisterRequest): AppResult<AuthSession> = error("not used in this test")

    override suspend fun refreshSession(request: RefreshRequest): AppResult<AuthSession> = error("not used in this test")
}
