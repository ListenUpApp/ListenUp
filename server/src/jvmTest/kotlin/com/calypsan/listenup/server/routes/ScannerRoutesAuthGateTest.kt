package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.ScannerService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.dto.scanner.ScanResultSummary
import com.calypsan.listenup.api.event.ScanEvent
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.plugins.JWT_PROVIDER
import com.calypsan.listenup.server.testing.testAuth
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.resources.Resources
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.client.rpcConfig
import kotlinx.rpc.krpc.serialization.json.json as rpcJson
import kotlinx.rpc.withService
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeout

/**
 * Auth-gate contract tests for the scanner surface (REST/SSE + kotlinx.rpc).
 *
 * The scanner used to be reachable with zero authentication. These tests pin the gate:
 *
 *  - Every `/api/v1/scan*` + `/sse/scan` endpoint requires a JWT (401 without one).
 *  - `POST /api/v1/scan` (the disk-heavy full-scan trigger) additionally requires ADMIN/ROOT (403 for a MEMBER).
 *  - `GET /api/v1/scan/last` stays visible to any authenticated user (MEMBER → 200).
 *  - `ScannerService` is no longer on the public RPC mount, and is reachable on the authed mount with a JWT.
 */
class ScannerRoutesAuthGateTest :
    FunSpec({

        test("POST /api/v1/scan without bearer token returns 401") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }
                val r: HttpResponse = client.post("/api/v1/scan")
                r.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("GET /api/v1/scan/last without bearer token returns 401") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }
                val r: HttpResponse = client.get("/api/v1/scan/last")
                r.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("GET /sse/scan without bearer token returns 401") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }
                val r: HttpResponse = client.get("/sse/scan")
                r.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("POST /api/v1/scan with a MEMBER principal returns 403") {
            testApplication {
                application {
                    install(ServerContentNegotiation) { json(contractJson) }
                    install(Resources)
                    install(SSE)
                    install(Authentication) {
                        testAuth(roleResolver = { if (it == "admin") UserRole.ADMIN else UserRole.MEMBER })
                    }
                    routing { authenticate(JWT_PROVIDER) { scannerRoutes(FakeScannerService, emptyFlow()) } }
                }
                client.post("/api/v1/scan") { bearerAuth("member") }.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("POST /api/v1/scan with an ADMIN principal returns 200") {
            testApplication {
                application {
                    install(ServerContentNegotiation) { json(contractJson) }
                    install(Resources)
                    install(SSE)
                    install(Authentication) {
                        testAuth(roleResolver = { if (it == "admin") UserRole.ADMIN else UserRole.MEMBER })
                    }
                    routing { authenticate(JWT_PROVIDER) { scannerRoutes(FakeScannerService, emptyFlow()) } }
                }
                client.post("/api/v1/scan") { bearerAuth("admin") }.status shouldBe HttpStatusCode.OK
            }
        }

        test("GET /api/v1/scan/last with a MEMBER principal is allowed") {
            testApplication {
                application {
                    install(ServerContentNegotiation) { json(contractJson) }
                    install(Resources)
                    install(SSE)
                    install(Authentication) {
                        testAuth(roleResolver = { if (it == "admin") UserRole.ADMIN else UserRole.MEMBER })
                    }
                    routing { authenticate(JWT_PROVIDER) { scannerRoutes(FakeScannerService, emptyFlow()) } }
                }
                client.get("/api/v1/scan/last") { bearerAuth("member") }.status shouldBe HttpStatusCode.OK
            }
        }

        test("public RPC mount no longer serves ScannerService") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }

                val rpcClient =
                    createClient {
                        install(WebSockets)
                        installKrpc()
                    }

                val service =
                    rpcClient
                        .rpc("ws://localhost/api/rpc/public") {
                            rpcConfig { serialization { rpcJson(contractJson) } }
                        }.withService<ScannerService>()

                shouldThrowAny { withTimeout(10.seconds) { service.lastScanResult() } }
            }
        }

        test("authed RPC mount serves ScannerService with a valid JWT") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }

                val setupClient = createClient { install(ContentNegotiation) { json(contractJson) } }
                val token =
                    setupClient
                        .post("/api/v1/auth/setup") {
                            contentType(ContentType.Application.Json)
                            setBody(RegisterRequest("root@scanner-gate.test", "x".repeat(MIN_PASSWORD_LENGTH), "Root"))
                        }.body<AppResult<AuthSession>>()
                        .shouldBeInstanceOf<AppResult.Success<AuthSession>>()
                        .data.accessToken.value

                val rpcClient =
                    createClient {
                        install(WebSockets)
                        installKrpc()
                    }

                val service =
                    rpcClient
                        .rpc("ws://localhost/api/rpc/authed") {
                            bearerAuth(token)
                            rpcConfig { serialization { rpcJson(contractJson) } }
                        }.withService<ScannerService>()

                service.lastScanResult().shouldBeInstanceOf<AppResult<ScanResult>>()
            }
        }
    })

private const val MIN_PASSWORD_LENGTH = 8

/**
 * Minimal in-process [ScannerService] for the role-gate route tests. `scanFull` and
 * `lastScanResult` return trivial Success values so the route's status-code contract
 * (401/403/200) is what's under test, not the scanner pipeline. `observeProgress` is unused.
 */
private object FakeScannerService : ScannerService {
    override suspend fun scanFull(): AppResult<ScanResultSummary> =
        AppResult.Success(
            ScanResultSummary(
                correlationId = "test",
                totalBooks = 0,
                added = 0,
                modified = 0,
                removed = 0,
                moved = 0,
                errors = 0,
                durationMs = 0,
                filesWalked = 0,
            ),
        )

    override suspend fun lastScanResult(): AppResult<ScanResult> =
        AppResult.Success(
            ScanResult(
                correlationId = "t",
                rootPath = "/tmp",
                books = emptyList(),
                changes = emptyList(),
                errors = emptyList(),
                durationMs = 0,
                filesWalked = 0,
                filesSkipped = 0,
            ),
        )

    override fun observeProgress(): Flow<RpcEvent<ScanEvent>> = error("unused in this test")
}
