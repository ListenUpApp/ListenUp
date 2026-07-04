package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.plugins.JWT_PROVIDER
import com.calypsan.listenup.server.services.BookReadsRepository
import com.calypsan.listenup.server.services.PublicProfileMaintainer
import com.calypsan.listenup.server.services.SearchReindexService
import com.calypsan.listenup.server.services.StatsRecorder
import com.calypsan.listenup.server.services.UserStatsBackfillService
import com.calypsan.listenup.server.services.UserStatsRepository
import com.calypsan.listenup.server.sync.BookSearchReindexer
import com.calypsan.listenup.server.sync.BookTagRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.PublicProfileRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.TagRepository
import com.calypsan.listenup.server.testing.activityRecorder
import com.calypsan.listenup.server.testing.withSqlDatabase
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
            withSqlDatabase {
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val statsRepo = UserStatsRepository(db = sql, bus = bus, registry = registry)
                val statsRecorder =
                    StatsRecorder(
                        sql = sql,
                        userStatsRepo = statsRepo,
                        bookReadsRepository = BookReadsRepository(db = sql),
                        publicProfileMaintainer =
                            PublicProfileMaintainer(
                                sql = sql,
                                publicProfileRepo = PublicProfileRepository(db = sql, bus = bus, registry = registry),
                            ),
                        activityRecorder = activityRecorder(bus = bus),
                        statsBackfill = UserStatsBackfillService(sql = sql, userStatsRepo = statsRepo),
                    )
                val reindexService = makeReindexService(sql, driver, bus, registry)

                testApplication {
                    application {
                        install(ContentNegotiation) { json(contractJson) }
                        install(Authentication) {
                            testAuthWithRole(JWT_PROVIDER, UserRole.ROOT)
                        }
                        routing {
                            authenticate(JWT_PROVIDER) {
                                adminRoutes(statsRecorder, reindexService)
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
            withSqlDatabase {
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val statsRepo = UserStatsRepository(db = sql, bus = bus, registry = registry)
                val statsRecorder =
                    StatsRecorder(
                        sql = sql,
                        userStatsRepo = statsRepo,
                        bookReadsRepository = BookReadsRepository(db = sql),
                        publicProfileMaintainer =
                            PublicProfileMaintainer(
                                sql = sql,
                                publicProfileRepo = PublicProfileRepository(db = sql, bus = bus, registry = registry),
                            ),
                        activityRecorder = activityRecorder(bus = bus),
                        statsBackfill = UserStatsBackfillService(sql = sql, userStatsRepo = statsRepo),
                    )
                val reindexService = makeReindexService(sql, driver, bus, registry)

                testApplication {
                    application {
                        install(ContentNegotiation) { json(contractJson) }
                        install(Authentication) {
                            testAuthWithRole(JWT_PROVIDER, UserRole.MEMBER)
                        }
                        routing {
                            authenticate(JWT_PROVIDER) {
                                adminRoutes(statsRecorder, reindexService)
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

        test("POST /api/v1/admin/search/reindex returns 200 for admin principal") {
            withSqlDatabase {
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val statsRepo = UserStatsRepository(db = sql, bus = bus, registry = registry)
                val statsRecorder =
                    StatsRecorder(
                        sql = sql,
                        userStatsRepo = statsRepo,
                        bookReadsRepository = BookReadsRepository(db = sql),
                        publicProfileMaintainer =
                            PublicProfileMaintainer(
                                sql = sql,
                                publicProfileRepo = PublicProfileRepository(db = sql, bus = bus, registry = registry),
                            ),
                        activityRecorder = activityRecorder(bus = bus),
                        statsBackfill = UserStatsBackfillService(sql = sql, userStatsRepo = statsRepo),
                    )
                val reindexService = makeReindexService(sql, driver, bus, registry)

                testApplication {
                    application {
                        install(ContentNegotiation) { json(contractJson) }
                        install(Authentication) {
                            testAuthWithRole(JWT_PROVIDER, UserRole.ADMIN)
                        }
                        routing {
                            authenticate(JWT_PROVIDER) {
                                adminRoutes(statsRecorder, reindexService)
                            }
                        }
                    }

                    val response =
                        client.post("/api/v1/admin/search/reindex") {
                            bearerAuth("admin-user")
                        }
                    response.status shouldBe HttpStatusCode.OK
                }
            }
        }

        test("POST /api/v1/admin/search/reindex returns 403 for non-admin principal") {
            withSqlDatabase {
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val statsRepo = UserStatsRepository(db = sql, bus = bus, registry = registry)
                val statsRecorder =
                    StatsRecorder(
                        sql = sql,
                        userStatsRepo = statsRepo,
                        bookReadsRepository = BookReadsRepository(db = sql),
                        publicProfileMaintainer =
                            PublicProfileMaintainer(
                                sql = sql,
                                publicProfileRepo = PublicProfileRepository(db = sql, bus = bus, registry = registry),
                            ),
                        activityRecorder = activityRecorder(bus = bus),
                        statsBackfill = UserStatsBackfillService(sql = sql, userStatsRepo = statsRepo),
                    )
                val reindexService = makeReindexService(sql, driver, bus, registry)

                testApplication {
                    application {
                        install(ContentNegotiation) { json(contractJson) }
                        install(Authentication) {
                            testAuthWithRole(JWT_PROVIDER, UserRole.MEMBER)
                        }
                        routing {
                            authenticate(JWT_PROVIDER) {
                                adminRoutes(statsRecorder, reindexService)
                            }
                        }
                    }

                    val response =
                        client.post("/api/v1/admin/search/reindex") {
                            bearerAuth("member-user")
                        }
                    response.status shouldBe HttpStatusCode.Forbidden
                }
            }
        }
    })

/** Builds a [SearchReindexService] over the test database for the route harness. */
private fun makeReindexService(
    sql: ListenUpDatabase,
    driver: app.cash.sqldelight.db.SqlDriver,
    bus: ChangeBus,
    registry: SyncRegistry,
): SearchReindexService {
    val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
    val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)
    return SearchReindexService(
        db = sql,
        driver = driver,
        reindexer = BookSearchReindexer(bookTagRepo, tagRepo, sql, driver),
    )
}

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
