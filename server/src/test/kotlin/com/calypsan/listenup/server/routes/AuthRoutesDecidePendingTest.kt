package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.PendingRegistrationDecision
import com.calypsan.listenup.api.dto.auth.PendingRegistrationOutcome
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.db.UserEntity
import com.calypsan.listenup.server.db.UserTable
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.koin.ktor.ext.get

/**
 * Wire-layer integration tests for `POST /api/v1/auth/pending-registrations/decision` —
 * exercises admin-gated approval flow including the approve→login follow-up.
 */
class AuthRoutesDecidePendingTest :
    FunSpec({

        suspend fun HttpClient.setupRootAndLogin(): AuthSession {
            post("/api/v1/auth/setup") {
                contentType(ContentType.Application.Json)
                setBody(RegisterRequest("root@x", "x".repeat(8), "Root"))
            }
            return post("/api/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(LoginRequest("root@x", "x".repeat(8)))
            }.body<AppResult<AuthSession>>()
                .shouldBeInstanceOf<AppResult.Success<AuthSession>>()
                .data
        }

        suspend fun HttpClient.registerPendingApplicant() {
            post("/api/v1/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(RegisterRequest("pending@x", "x".repeat(8), "Pending"))
            }.body<AppResult<RegisterResult>>()
                .shouldBeInstanceOf<AppResult.Success<RegisterResult>>()
                .data
                .shouldBeInstanceOf<RegisterResult.PendingApproval>()
        }

        fun Database.findUserIdByEmail(email: String): UserId =
            transaction(this) {
                UserId(
                    UserEntity
                        .find { UserTable.emailNormalized eq email }
                        .single()
                        .id.value,
                )
            }

        test("decide approve flips status to ACTIVE, applicant can then login") {
            testApplication {
                useIsolatedTestConfig(registrationPolicy = "APPROVAL_QUEUE")
                lateinit var db: Database
                application {
                    module()
                    db = get()
                }
                val client = createClient { install(ContentNegotiation) { json() } }

                val root = client.setupRootAndLogin()
                client.registerPendingApplicant()
                val pendingId = db.findUserIdByEmail("pending@x")

                val decision =
                    client.post("/api/v1/auth/pending-registrations/decision") {
                        bearerAuth(root.accessToken.value)
                        contentType(ContentType.Application.Json)
                        setBody(PendingRegistrationDecision(pendingId, approved = true))
                    }

                decision.status shouldBe HttpStatusCode.OK
                decision
                    .body<AppResult<PendingRegistrationOutcome>>()
                    .shouldBeInstanceOf<AppResult.Success<PendingRegistrationOutcome>>()
                    .data shouldBe PendingRegistrationOutcome.Approved

                val loginAfter =
                    client.post("/api/v1/auth/login") {
                        contentType(ContentType.Application.Json)
                        setBody(LoginRequest("pending@x", "x".repeat(8)))
                    }
                loginAfter.status shouldBe HttpStatusCode.OK
            }
        }

        test("decide deny flips status to DENIED, applicant login returns AccountDenied") {
            testApplication {
                useIsolatedTestConfig(registrationPolicy = "APPROVAL_QUEUE")
                lateinit var db: Database
                application {
                    module()
                    db = get()
                }
                val client = createClient { install(ContentNegotiation) { json() } }

                val root = client.setupRootAndLogin()
                client.registerPendingApplicant()
                val pendingId = db.findUserIdByEmail("pending@x")

                val decision =
                    client.post("/api/v1/auth/pending-registrations/decision") {
                        bearerAuth(root.accessToken.value)
                        contentType(ContentType.Application.Json)
                        setBody(PendingRegistrationDecision(pendingId, approved = false))
                    }

                decision.status shouldBe HttpStatusCode.OK
                decision
                    .body<AppResult<PendingRegistrationOutcome>>()
                    .shouldBeInstanceOf<AppResult.Success<PendingRegistrationOutcome>>()
                    .data shouldBe PendingRegistrationOutcome.Denied

                val loginAfter =
                    client.post("/api/v1/auth/login") {
                        contentType(ContentType.Application.Json)
                        setBody(LoginRequest("pending@x", "x".repeat(8)))
                    }
                loginAfter.status shouldBe HttpStatusCode.Forbidden
                loginAfter
                    .body<AppResult<AuthSession>>()
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<AuthError.AccountDenied>()
            }
        }

        test("decide as a non-admin caller returns PermissionDenied (403)") {
            testApplication {
                useIsolatedTestConfig(registrationPolicy = "APPROVAL_QUEUE")
                lateinit var db: Database
                application {
                    module()
                    db = get()
                }
                val client = createClient { install(ContentNegotiation) { json() } }

                val root = client.setupRootAndLogin()

                // Register `member@x` (PENDING_APPROVAL on this policy), root approves
                // them so they become ACTIVE and can log in as a non-admin caller.
                client.post("/api/v1/auth/register") {
                    contentType(ContentType.Application.Json)
                    setBody(RegisterRequest("member@x", "x".repeat(8), "Member"))
                }
                val memberId = db.findUserIdByEmail("member@x")
                client.post("/api/v1/auth/pending-registrations/decision") {
                    bearerAuth(root.accessToken.value)
                    contentType(ContentType.Application.Json)
                    setBody(PendingRegistrationDecision(memberId, approved = true))
                }
                val memberSession =
                    client
                        .post("/api/v1/auth/login") {
                            contentType(ContentType.Application.Json)
                            setBody(LoginRequest("member@x", "x".repeat(8)))
                        }.body<AppResult<AuthSession>>()
                        .shouldBeInstanceOf<AppResult.Success<AuthSession>>()
                        .data

                client.registerPendingApplicant()
                val pendingId = db.findUserIdByEmail("pending@x")

                val r =
                    client.post("/api/v1/auth/pending-registrations/decision") {
                        bearerAuth(memberSession.accessToken.value)
                        contentType(ContentType.Application.Json)
                        setBody(PendingRegistrationDecision(pendingId, approved = true))
                    }

                r.status shouldBe HttpStatusCode.Forbidden
                r
                    .body<AppResult<PendingRegistrationOutcome>>()
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<AuthError.PermissionDenied>()
            }
        }

        test("decide on a non-pending target returns PermissionDenied") {
            testApplication {
                useIsolatedTestConfig()
                lateinit var db: Database
                application {
                    module()
                    db = get()
                }
                val client = createClient { install(ContentNegotiation) { json() } }

                val root = client.setupRootAndLogin()
                // alice is registered as ACTIVE on an OPEN policy — never PENDING_APPROVAL.
                client.post("/api/v1/auth/register") {
                    contentType(ContentType.Application.Json)
                    setBody(RegisterRequest("alice@x", "x".repeat(8), "Alice"))
                }
                val aliceId = db.findUserIdByEmail("alice@x")

                val r =
                    client.post("/api/v1/auth/pending-registrations/decision") {
                        bearerAuth(root.accessToken.value)
                        contentType(ContentType.Application.Json)
                        setBody(PendingRegistrationDecision(aliceId, approved = true))
                    }

                r.status shouldBe HttpStatusCode.Forbidden
                r
                    .body<AppResult<PendingRegistrationOutcome>>()
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<AuthError.PermissionDenied>()
            }
        }

        test("decide without bearer returns 401") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }
                val client = createClient { install(ContentNegotiation) { json() } }

                val r =
                    client.post("/api/v1/auth/pending-registrations/decision") {
                        contentType(ContentType.Application.Json)
                        setBody(PendingRegistrationDecision(UserId("any"), approved = true))
                    }

                r.status shouldBe HttpStatusCode.Unauthorized
            }
        }
    })
