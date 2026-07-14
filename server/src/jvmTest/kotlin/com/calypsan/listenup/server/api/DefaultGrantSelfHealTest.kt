@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.auth.AuthServiceImpl
import com.calypsan.listenup.server.auth.JwtConfiguration
import com.calypsan.listenup.server.auth.Argon2Limiter
import com.calypsan.listenup.server.auth.PasswordHasher
import com.calypsan.listenup.server.auth.RefreshTokenGenerator
import com.calypsan.listenup.server.auth.RefreshTokenHasher
import com.calypsan.listenup.server.auth.SessionIssuer
import com.calypsan.listenup.server.auth.SessionService
import com.calypsan.listenup.server.services.LibraryRegistry
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionGrantRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Self-heal + idempotency tests for [DefaultAllBooksGrantIssuer] and the login path in
 * [AuthServiceImpl].
 *
 * Tests:
 * 1. Idempotency — calling [DefaultAllBooksGrantIssuer.grantDefaultAllBooks] twice for the same
 *    MEMBER produces exactly ONE live grant (no exception, no duplicate).
 * 2. Self-heal on login — a MEMBER whose ALL_BOOKS grant was somehow deleted (simulating a
 *    first-time failure) has it restored the next time they log in.
 *
 * Uses real in-memory Flyway-migrated SQLite with a bootstrapped library, exactly as
 * [DefaultGrantTest] does.
 */
class DefaultGrantSelfHealTest :
    FunSpec({

        val fixedClock = FixedClock(Instant.parse("2026-06-19T12:00:00Z"))
        val pepper = "x".repeat(32).toByteArray()

        data class Deps(
            val authSvc: AuthServiceImpl,
            val grantRepository: CollectionGrantRepository,
            val collectionRepository: CollectionRepository,
            val libraryRegistry: LibraryRegistry,
            val grantIssuer: DefaultAllBooksGrantIssuer,
        )

        fun buildDeps(db: SqlTestDatabases): Deps {
            val syncRegistry = SyncRegistry()
            val bus = ChangeBus()
            val collectionRepository = CollectionRepository(db.sql, bus, syncRegistry, driver = db.driver)
            val grantRepository = CollectionGrantRepository(db.sql, bus, syncRegistry, driver = db.driver)
            val libraryRegistry = LibraryRegistry(db.sql)

            val sessions =
                SessionService(db.sql, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = fixedClock)
            val jwt = JwtConfiguration("x".repeat(32), "listenup", "listenup-client", 15.minutes, fixedClock)
            val sessionIssuer = SessionIssuer(sessions, jwt, fixedClock)
            val hasher = PasswordHasher()
            val settings = ServerSettingsRepository(db.sql, default = RegistrationPolicy.OPEN)

            val grantIssuer =
                DefaultAllBooksGrantIssuer(
                    collectionGrantRepository = grantRepository,
                    collectionRepository = collectionRepository,
                    libraryRegistry = libraryRegistry,
                    clock = fixedClock,
                )

            val authSvc =
                AuthServiceImpl(
                    db = db.sql,
                    sessions = sessions,
                    hasher = Argon2Limiter(hasher),
                    jwt = jwt,
                    sessionIssuer = sessionIssuer,
                    clock = fixedClock,
                    settings = settings,
                    defaultGrantIssuer = grantIssuer,
                )

            return Deps(authSvc, grantRepository, collectionRepository, libraryRegistry, grantIssuer)
        }

        /** Count active (non-tombstoned) USER grants for [userId]. */
        fun SqlTestDatabases.liveGrantCountForUser(userId: String): Int =
            sql.collectionGrantsQueries
                .listActiveUserGrantsForPrincipal(principal_id = userId)
                .executeAsList()
                .size

        /**
         * Simulate a "missing grant" by soft-deleting (tombstoning) the existing grant for [userId]
         * on the ALL_BOOKS collection. This mimics the scenario where [grantDefaultAllBooks] failed
         * at registration time.
         */
        fun SqlTestDatabases.tombstoneAllBooksGrant(
            userId: String,
            allBooksId: String,
            now: Long,
        ) {
            sql.transaction {
                sql.collectionGrantsQueries.tombstoneGrantForUser(
                    deleted_at = now,
                    collection_id = allBooksId,
                    principal_id = userId,
                )
            }
        }

        // ── Test 1: idempotency ────────────────────────────────────────────────────

        test("grantDefaultAllBooks is idempotent — calling twice yields exactly one live grant") {
            withSqlDatabase {
                val db = this
                runTest {
                    val deps = buildDeps(db)
                    // Bootstrap library so ALL_BOOKS exists.
                    deps.libraryRegistry.currentLibrary()

                    // Seed root first so register can proceed.
                    deps.authSvc.setupRoot(RegisterRequest("root@example.com", "password123", "Root"))

                    // Register a MEMBER to get a userId (creates the first grant).
                    val result =
                        deps.authSvc.register(
                            RegisterRequest("member@example.com", "password123", "Member"),
                        )
                    val userId =
                        (result as AppResult.Success)
                            .data
                            .let { r ->
                                when (r) {
                                    is com.calypsan.listenup.api.dto.auth.RegisterResult.Authenticated -> {
                                        r.session.user.id.value
                                    }

                                    is com.calypsan.listenup.api.dto.auth.RegisterResult.PendingApproval -> {
                                        r.userId.value
                                    }
                                }
                            }

                    // Sanity: exactly 1 grant after registration.
                    db.liveGrantCountForUser(userId) shouldBe 1

                    // Call the issuer a second time — must be a no-op (idempotent).
                    deps.grantIssuer.grantDefaultAllBooks(userId, com.calypsan.listenup.server.db.UserRoleColumn.MEMBER)

                    // Must still be exactly 1 live grant — not 2.
                    db.liveGrantCountForUser(userId) shouldBe 1
                }
            }
        }

        // ── Test 2: self-heal on login ─────────────────────────────────────────────

        test("login self-heals a missing ALL_BOOKS grant for MEMBER") {
            withSqlDatabase {
                val db = this
                runTest {
                    val deps = buildDeps(db)
                    // Bootstrap library so ALL_BOOKS exists.
                    val libraryId = deps.libraryRegistry.currentLibrary().value
                    val allBooksId =
                        deps.collectionRepository
                            .findSystemCollection(libraryId, SYSTEM_TYPE_ALL_BOOKS)!!
                            .id

                    // Seed root first.
                    deps.authSvc.setupRoot(RegisterRequest("root@example.com", "password123", "Root"))

                    // Register a MEMBER — this normally creates the grant.
                    val result =
                        deps.authSvc.register(
                            RegisterRequest("member@example.com", "password123", "Member"),
                        )
                    val userId =
                        (result as AppResult.Success)
                            .data
                            .let { r ->
                                when (r) {
                                    is com.calypsan.listenup.api.dto.auth.RegisterResult.Authenticated -> {
                                        r.session.user.id.value
                                    }

                                    is com.calypsan.listenup.api.dto.auth.RegisterResult.PendingApproval -> {
                                        r.userId.value
                                    }
                                }
                            }

                    // Sanity: 1 live grant exists after registration.
                    db.liveGrantCountForUser(userId) shouldBe 1

                    // Simulate the failure scenario: tombstone the grant.
                    val now = fixedClock.now().toEpochMilliseconds()
                    db.tombstoneAllBooksGrant(userId, allBooksId, now)

                    // Confirm the grant is gone.
                    db.liveGrantCountForUser(userId) shouldBe 0

                    // Member logs in — this should self-heal the grant.
                    val loginResult =
                        deps.authSvc.login(
                            LoginRequest("member@example.com", "password123"),
                        )

                    // Login must succeed.
                    (loginResult is AppResult.Success) shouldBe true

                    // The grant must now be restored.
                    db.liveGrantCountForUser(userId) shouldBe 1
                }
            }
        }
    })
