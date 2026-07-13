@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.PendingRegistrationDecision
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.auth.AuthServiceImpl
import com.calypsan.listenup.server.auth.InviteCodeGenerator
import com.calypsan.listenup.server.auth.JwtConfiguration
import com.calypsan.listenup.server.auth.Argon2Limiter
import com.calypsan.listenup.server.auth.PasswordHasher
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.RefreshTokenGenerator
import com.calypsan.listenup.server.auth.RefreshTokenHasher
import com.calypsan.listenup.server.auth.RegistrationBroadcaster
import com.calypsan.listenup.server.auth.RegistrationPolicyBroadcaster
import com.calypsan.listenup.server.auth.SessionIssuer
import com.calypsan.listenup.server.auth.SessionService
import com.calypsan.listenup.server.auth.UserPrincipal
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
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Integration tests verifying that a new MEMBER user receives a default ALL_BOOKS grant
 * on registration and invite claim, and that ROOT/ADMIN users do not.
 *
 * Also verifies that under APPROVAL_QUEUE policy:
 * - A PENDING_APPROVAL registrant does NOT receive the grant at registration time.
 * - The grant IS issued when an admin approves the registration.
 * - A denied registrant receives no grant.
 *
 * Uses real in-memory Flyway-migrated SQLite with a bootstrapped library (so ALL_BOOKS
 * exists). No mocks — [DefaultAllBooksGrantIssuer] is fully wired.
 */
class DefaultGrantTest :
    FunSpec({

        val fixedClock = FixedClock(Instant.parse("2026-06-19T12:00:00Z"))
        val pepper = "x".repeat(32).toByteArray()

        data class Deps(
            val authSvc: AuthServiceImpl,
            val inviteSvc: InviteServiceImpl,
            val adminSvc: AdminUserServiceImpl,
            val libraryRegistry: LibraryRegistry,
            val collectionRepository: CollectionRepository,
        )

        fun buildDeps(
            db: SqlTestDatabases,
            registrationPolicy: RegistrationPolicy = RegistrationPolicy.OPEN,
        ): Deps {
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
            val settings = ServerSettingsRepository(db.sql, default = registrationPolicy)

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

            val inviteSvc =
                InviteServiceImpl(
                    db = db.sql,
                    codeGenerator = InviteCodeGenerator(),
                    hasher = Argon2Limiter(hasher),
                    sessionIssuer = sessionIssuer,
                    serverName = "Test",
                    clock = fixedClock,
                    defaultGrantIssuer = grantIssuer,
                )

            val adminSvc =
                AdminUserServiceImpl(
                    sql = db.sql,
                    sessions = sessions,
                    settings = settings,
                    registrationBroadcaster = RegistrationBroadcaster(),
                    registrationPolicyBroadcaster = RegistrationPolicyBroadcaster(),
                    bus = bus,
                    clock = fixedClock,
                    defaultGrantIssuer = grantIssuer,
                )

            return Deps(authSvc, inviteSvc, adminSvc, libraryRegistry, collectionRepository)
        }

        fun SqlTestDatabases.grantRowsForUser(userId: String) =
            sql.collectionGrantsQueries
                .listActiveUserGrantsForPrincipal(principal_id = userId)
                .executeAsList()

        // ── register (MEMBER, OPEN policy) ────────────────────────────────────────

        test("register creates an ALL_BOOKS grant for the new MEMBER user") {
            withSqlDatabase {
                val db = this
                runTest {
                    val deps = buildDeps(db)
                    val authSvc = deps.authSvc
                    val libraryRegistry = deps.libraryRegistry
                    val collectionRepository = deps.collectionRepository
                    // Bootstrap the library so ALL_BOOKS exists.
                    libraryRegistry.currentLibrary()

                    // Seed root first so the DB is non-empty (register requires this).
                    authSvc.setupRoot(RegisterRequest("root@example.com", "password123", "Root"))

                    val result =
                        authSvc.register(
                            RegisterRequest("member@example.com", "password123", "Member"),
                        )
                    val userId =
                        when (val r = result) {
                            is AppResult.Success -> {
                                when (val outcome = r.data) {
                                    is com.calypsan.listenup.api.dto.auth.RegisterResult.Authenticated -> {
                                        outcome.session.user.id.value
                                    }

                                    is com.calypsan.listenup.api.dto.auth.RegisterResult.PendingApproval -> {
                                        outcome.userId.value
                                    }
                                }
                            }

                            is AppResult.Failure -> {
                                error("register failed: ${r.error}")
                            }
                        }

                    val libraryId = libraryRegistry.currentLibrary().value
                    val allBooksId = collectionRepository.findSystemCollection(libraryId, SYSTEM_TYPE_ALL_BOOKS)?.id
                    allBooksId.shouldNotBeNull()

                    val grants = db.grantRowsForUser(userId)
                    grants.size shouldBe 1
                    grants[0].principal_type shouldBe "USER"
                    grants[0].principal_id shouldBe userId
                    grants[0].collection_id shouldBe allBooksId
                    grants[0].deleted_at.shouldBeNull()
                }
            }
        }

        // ── setupRoot (ROOT) ──────────────────────────────────────────────────────

        test("setupRoot creates NO grant for the ROOT user") {
            withSqlDatabase {
                val db = this
                runTest {
                    val deps = buildDeps(db)
                    val authSvc = deps.authSvc
                    val libraryRegistry = deps.libraryRegistry
                    libraryRegistry.currentLibrary()

                    val session =
                        authSvc.setupRoot(
                            RegisterRequest("root@example.com", "password123", "Root"),
                        )
                    val userId =
                        (session as AppResult.Success)
                            .data.user.id.value

                    val grants = db.grantRowsForUser(userId)
                    grants.size shouldBe 0
                }
            }
        }

        // ── claimInvite (ADMIN role invite) ──────────────────────────────────────

        test("claimInvite with ADMIN role invite creates NO grant") {
            withSqlDatabase {
                val db = this
                runTest {
                    val deps = buildDeps(db)
                    val authSvc = deps.authSvc
                    val inviteSvc = deps.inviteSvc
                    val libraryRegistry = deps.libraryRegistry
                    libraryRegistry.currentLibrary()

                    // Need a root user to seed the DB and create invites.
                    val rootSession =
                        authSvc.setupRoot(
                            RegisterRequest("root@example.com", "password123", "Root"),
                        )
                    val rootId =
                        (rootSession as AppResult.Success)
                            .data.user.id.value

                    val adminPrincipal =
                        PrincipalProvider {
                            UserPrincipal(UserId(rootId), SessionId("session-root"), UserRole.ROOT)
                        }
                    val adminInvite =
                        inviteSvc
                            .copyWith(adminPrincipal)
                            .createInvite("admin@example.com", "Admin", UserRole.ADMIN, null)
                    val code = (adminInvite as AppResult.Success).data.code

                    val claimResult = inviteSvc.claimInvite(code, "password123", "Admin User", null)
                    val userId =
                        (claimResult as AppResult.Success)
                            .data.user.id.value

                    val grants = db.grantRowsForUser(userId)
                    grants.size shouldBe 0
                }
            }
        }

        // ── claimInvite (MEMBER role invite) ─────────────────────────────────────

        test("claimInvite with MEMBER role invite creates an ALL_BOOKS grant") {
            withSqlDatabase {
                val db = this
                runTest {
                    val deps = buildDeps(db)
                    val authSvc = deps.authSvc
                    val inviteSvc = deps.inviteSvc
                    val libraryRegistry = deps.libraryRegistry
                    val collectionRepository = deps.collectionRepository
                    libraryRegistry.currentLibrary()

                    val rootSession =
                        authSvc.setupRoot(
                            RegisterRequest("root@example.com", "password123", "Root"),
                        )
                    val rootId =
                        (rootSession as AppResult.Success)
                            .data.user.id.value

                    val adminPrincipal =
                        PrincipalProvider {
                            UserPrincipal(UserId(rootId), SessionId("session-root"), UserRole.ROOT)
                        }
                    val memberInvite =
                        inviteSvc
                            .copyWith(adminPrincipal)
                            .createInvite("member2@example.com", "Member2", UserRole.MEMBER, null)
                    val code = (memberInvite as AppResult.Success).data.code

                    val claimResult = inviteSvc.claimInvite(code, "password123", "Member User", null)
                    val userId =
                        (claimResult as AppResult.Success)
                            .data.user.id.value

                    val libraryId = libraryRegistry.currentLibrary().value
                    val allBooksId = collectionRepository.findSystemCollection(libraryId, SYSTEM_TYPE_ALL_BOOKS)?.id
                    allBooksId.shouldNotBeNull()

                    val grants = db.grantRowsForUser(userId)
                    grants.size shouldBe 1
                    grants[0].principal_type shouldBe "USER"
                    grants[0].principal_id shouldBe userId
                    grants[0].collection_id shouldBe allBooksId
                    grants[0].deleted_at.shouldBeNull()
                }
            }
        }

        // ── APPROVAL_QUEUE: no grant at registration, grant on approval ───────────

        test("register under APPROVAL_QUEUE policy creates NO grant for the PENDING_APPROVAL user") {
            withSqlDatabase {
                val db = this
                runTest {
                    val deps = buildDeps(db, RegistrationPolicy.APPROVAL_QUEUE)
                    val authSvc = deps.authSvc
                    val libraryRegistry = deps.libraryRegistry
                    libraryRegistry.currentLibrary()

                    // Seed root so the DB is non-empty and registration is allowed.
                    authSvc.setupRoot(RegisterRequest("root@example.com", "password123", "Root"))

                    val result =
                        authSvc.register(
                            RegisterRequest("pending@example.com", "password123", "Pending"),
                        )
                    val pendingUserId =
                        ((result as AppResult.Success).data as com.calypsan.listenup.api.dto.auth.RegisterResult.PendingApproval)
                            .userId.value

                    // No grant should exist yet — the user is PENDING_APPROVAL.
                    val grants = db.grantRowsForUser(pendingUserId)
                    grants.size shouldBe 0
                }
            }
        }

        test("admin approval of a PENDING_APPROVAL MEMBER user issues the ALL_BOOKS grant") {
            withSqlDatabase {
                val db = this
                runTest {
                    val deps = buildDeps(db, RegistrationPolicy.APPROVAL_QUEUE)
                    val authSvc = deps.authSvc
                    val adminSvc = deps.adminSvc
                    val libraryRegistry = deps.libraryRegistry
                    val collectionRepository = deps.collectionRepository
                    libraryRegistry.currentLibrary()

                    // Seed root so the DB is non-empty.
                    val rootSession =
                        authSvc.setupRoot(RegisterRequest("root@example.com", "password123", "Root"))
                    val rootId =
                        (rootSession as AppResult.Success)
                            .data.user.id.value

                    // Register as MEMBER — results in PENDING_APPROVAL.
                    val regResult =
                        authSvc.register(RegisterRequest("pending@example.com", "password123", "Pending"))
                    val pendingUserId =
                        ((regResult as AppResult.Success).data as com.calypsan.listenup.api.dto.auth.RegisterResult.PendingApproval)
                            .userId.value

                    // Confirm no grant exists pre-approval.
                    db.grantRowsForUser(pendingUserId).size shouldBe 0

                    // Admin approves the registration.
                    val rootPrincipal =
                        PrincipalProvider {
                            UserPrincipal(UserId(rootId), SessionId("session-root"), UserRole.ROOT)
                        }
                    adminSvc
                        .copyWith(rootPrincipal)
                        .decidePendingRegistration(PendingRegistrationDecision(UserId(pendingUserId), approved = true))

                    // Grant must now exist and point at ALL_BOOKS.
                    val libraryId = libraryRegistry.currentLibrary().value
                    val allBooksId = collectionRepository.findSystemCollection(libraryId, SYSTEM_TYPE_ALL_BOOKS)?.id
                    allBooksId.shouldNotBeNull()

                    val grants = db.grantRowsForUser(pendingUserId)
                    grants.size shouldBe 1
                    grants[0].principal_type shouldBe "USER"
                    grants[0].principal_id shouldBe pendingUserId
                    grants[0].collection_id shouldBe allBooksId
                    grants[0].deleted_at.shouldBeNull()
                }
            }
        }

        test("admin denial of a PENDING_APPROVAL user issues NO grant") {
            withSqlDatabase {
                val db = this
                runTest {
                    val deps = buildDeps(db, RegistrationPolicy.APPROVAL_QUEUE)
                    val authSvc = deps.authSvc
                    val adminSvc = deps.adminSvc
                    val libraryRegistry = deps.libraryRegistry
                    libraryRegistry.currentLibrary()

                    val rootSession =
                        authSvc.setupRoot(RegisterRequest("root@example.com", "password123", "Root"))
                    val rootId =
                        (rootSession as AppResult.Success)
                            .data.user.id.value

                    val regResult =
                        authSvc.register(RegisterRequest("pending@example.com", "password123", "Pending"))
                    val pendingUserId =
                        ((regResult as AppResult.Success).data as com.calypsan.listenup.api.dto.auth.RegisterResult.PendingApproval)
                            .userId.value

                    val rootPrincipal =
                        PrincipalProvider {
                            UserPrincipal(UserId(rootId), SessionId("session-root"), UserRole.ROOT)
                        }
                    adminSvc
                        .copyWith(rootPrincipal)
                        .decidePendingRegistration(PendingRegistrationDecision(UserId(pendingUserId), approved = false))

                    // Denied user must have no grant.
                    val grants = db.grantRowsForUser(pendingUserId)
                    grants.size shouldBe 0
                }
            }
        }
    })
