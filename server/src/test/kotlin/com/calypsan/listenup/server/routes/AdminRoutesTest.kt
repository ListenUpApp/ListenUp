package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.plugins.JWT_PROVIDER
import com.calypsan.listenup.server.services.UserStatsBackfillService
import com.calypsan.listenup.server.services.UserStatsRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.auth.AuthenticationConfig

/**
 * Route-level contract tests for [adminRoutes].
 *
 * Uses a minimal test harness (no full `module()` / Koin startup): constructs
 * only the repositories and service under test, installs a role-aware test auth
 * provider, mounts [adminRoutes] directly, and verifies HTTP status codes.
 *
 * Two roles are exercised:
 *  - ROOT → 200 (backfill runs)
 *  - MEMBER → 403 (permission denied)
 */
class AdminRoutesTest :
    FunSpec({

        test("POST /api/v1/admin/stats/backfill returns 200 for admin principal") {
            withInMemoryDatabase {
                val db = this
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val statsRepo = UserStatsRepository(db = db, bus = bus, registry = registry)
                val backfillService = UserStatsBackfillService(db = db, userStatsRepo = statsRepo)

                testApplication {
                    application {
                        install(ContentNegotiation) { json(contractJson) }
                        install(Authentication) {
                            testAuthWithRole(JWT_PROVIDER, UserRole.ROOT)
                        }
                        routing {
                            authenticate(JWT_PROVIDER) {
                                adminRoutes(backfillService)
                            }
                        }
                    }

                    val response =
                        client.post("/api/v1/admin/stats/backfill?userId=u1") {
                            bearerAuth("admin-user")
                        }
                    response.status shouldBe HttpStatusCode.OK
                }
            }
        }

        test("POST /api/v1/admin/stats/backfill returns 403 for non-admin principal") {
            withInMemoryDatabase {
                val db = this
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val statsRepo = UserStatsRepository(db = db, bus = bus, registry = registry)
                val backfillService = UserStatsBackfillService(db = db, userStatsRepo = statsRepo)

                testApplication {
                    application {
                        install(ContentNegotiation) { json(contractJson) }
                        install(Authentication) {
                            testAuthWithRole(JWT_PROVIDER, UserRole.MEMBER)
                        }
                        routing {
                            authenticate(JWT_PROVIDER) {
                                adminRoutes(backfillService)
                            }
                        }
                    }

                    val response =
                        client.post("/api/v1/admin/stats/backfill?userId=u1") {
                            bearerAuth("member-user")
                        }
                    response.status shouldBe HttpStatusCode.Forbidden
                }
            }
        }
    })

/**
 * Installs a test auth provider that always authenticates with the given [role].
 * Used by [AdminRoutesTest] to exercise both admin (200) and non-admin (403) paths.
 */
private fun AuthenticationConfig.testAuthWithRole(
    name: String,
    role: UserRole,
) {
    val config = object : AuthenticationProvider.Config(name) {}
    register(
        object : AuthenticationProvider(config) {
            override suspend fun onAuthenticate(context: AuthenticationContext) {
                context.principal(
                    UserPrincipal(
                        userId = UserId("test-user"),
                        sessionId = SessionId("test-session"),
                        role = role,
                    ),
                )
            }
        },
    )
}
