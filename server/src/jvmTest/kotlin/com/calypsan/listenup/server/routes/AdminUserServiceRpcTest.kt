package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.AdminUserService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.auth.User
import com.calypsan.listenup.api.dto.auth.UserStatus
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.client.rpcConfig
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.withService

/**
 * End-to-end reachability test for [AdminUserService.listUsers] over the authed kotlinx.rpc
 * surface — the exact path the Android admin screen drives (`AdminRepositoryImpl` → RPC proxy).
 *
 * Regression: with a PENDING_APPROVAL applicant present, the admin page showed
 * "Something Went Wrong on the Server" and the pending list never appeared. The REST surface
 * serializes a pending [User] fine; this test exercises the RPC transport specifically.
 */
class AdminUserServiceRpcTest :
    FunSpec({

        suspend fun HttpClient.mintRootToken(): String {
            post("/api/v1/auth/setup") {
                contentType(ContentType.Application.Json)
                setBody(RegisterRequest("root@rpc-admin.example", "x".repeat(8), "Root"))
            }
            return post("/api/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(LoginRequest("root@rpc-admin.example", "x".repeat(8)))
            }.body<AppResult<AuthSession>>()
                .shouldBeInstanceOf<AppResult.Success<AuthSession>>()
                .data
                .accessToken
                .value
        }

        suspend fun HttpClient.registerPending(name: String): String =
            post("/api/v1/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(RegisterRequest("$name@rpc-admin.example", "y".repeat(8), name))
            }.body<AppResult<RegisterResult>>()
                .shouldBeInstanceOf<AppResult.Success<RegisterResult>>()
                .data
                .shouldBeInstanceOf<RegisterResult.PendingApproval>()
                .userId
                .value

        test("AdminUserService.listUsers over RPC excludes a PENDING_APPROVAL applicant (active roster only)") {
            testApplication {
                useIsolatedTestConfig(registrationPolicy = "APPROVAL_QUEUE")
                application { module() }

                val restClient = createClient { install(ContentNegotiation) { json(contractJson) } }
                val token = restClient.mintRootToken()
                val pendingId = restClient.registerPending("pending")

                val rpcClient =
                    createClient {
                        install(WebSockets)
                        installKrpc()
                    }

                val service =
                    rpcClient
                        .rpc("ws://localhost/api/rpc/authed") {
                            rpcConfig { serialization { json(contractJson) } }
                            bearerAuth(token)
                        }.withService<AdminUserService>()

                val users = service.listUsers().shouldBeInstanceOf<AppResult.Success<List<User>>>().data
                // The active roster excludes a pending applicant (it belongs only in the
                // pending section); the root admin (ACTIVE) is present. Serialization round-trips
                // cleanly over RPC either way.
                users.any { it.id.value == pendingId } shouldBe false
                users.all { it.status == UserStatus.ACTIVE } shouldBe true
            }
        }

        test("AdminUserService.listPendingUsers over RPC returns the pending applicant") {
            testApplication {
                useIsolatedTestConfig(registrationPolicy = "APPROVAL_QUEUE")
                application { module() }

                val restClient = createClient { install(ContentNegotiation) { json(contractJson) } }
                val token = restClient.mintRootToken()
                val pendingId = restClient.registerPending("pending")

                val rpcClient =
                    createClient {
                        install(WebSockets)
                        installKrpc()
                    }

                val service =
                    rpcClient
                        .rpc("ws://localhost/api/rpc/authed") {
                            rpcConfig { serialization { json(contractJson) } }
                            bearerAuth(token)
                        }.withService<AdminUserService>()

                val pending = service.listPendingUsers().shouldBeInstanceOf<AppResult.Success<List<User>>>().data
                pending.map { it.id.value } shouldBe listOf(pendingId)
            }
        }
    })
