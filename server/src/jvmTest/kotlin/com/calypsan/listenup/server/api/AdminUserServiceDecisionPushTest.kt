@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.PendingRegistrationDecision
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.auth.UserStatus
import com.calypsan.listenup.api.push.PushPayload
import com.calypsan.listenup.api.push.PushPlatform
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.RefreshTokenGenerator
import com.calypsan.listenup.server.auth.RefreshTokenHasher
import com.calypsan.listenup.server.auth.RegistrationBroadcaster
import com.calypsan.listenup.server.auth.RegistrationPolicyBroadcaster
import com.calypsan.listenup.server.auth.SessionService
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.db.UserStatusColumn
import com.calypsan.listenup.server.push.PushNotifier
import com.calypsan.listenup.server.push.PushWatchKind
import com.calypsan.listenup.server.push.PushWatchTokenStore
import com.calypsan.listenup.server.services.AdminUserRosterMaintainer
import com.calypsan.listenup.server.services.LibraryRegistry
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.sync.AdminUserRosterRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionGrantRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.noOpPublicProfileMaintainer
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.testPasswordResetService
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant

/**
 * Pins CORRECTNESS-05: a [PushNotifier] that violates the "MUST NOT throw" contract must not take
 * down [AdminUserServiceImpl.decidePendingRegistration]'s other post-commit side effects — watch-token
 * eviction, the default ALL_BOOKS grant, and the admin-roster refresh must all still land. The
 * throwing notifier is dispatched fire-and-forget (never awaited on the RPC path), so none of these
 * assertions depend on it ever completing — they were true the instant [decidePendingRegistration]
 * returned, before this test's own [AdminUserServiceImpl] (built with the class's default detached
 * `appScope`) even got a chance to run the doomed push.
 */
class AdminUserServiceDecisionPushTest :
    FunSpec({
        val pepper = "x".repeat(32).toByteArray()
        val fixedClock = FixedClock(Instant.fromEpochMilliseconds(1_700_000_000_000L))

        fun principalFor(
            userId: String,
            role: UserRole,
        ): PrincipalProvider = PrincipalProvider { UserPrincipal(UserId(userId), SessionId("session-$userId"), role) }

        /** Seeds a PENDING_APPROVAL user directly via SQLDelight (the shared fixture only seeds ACTIVE). */
        fun SqlTestDatabases.seedPendingUser(userId: String) {
            sql.transaction {
                sql.usersQueries.insert(
                    id = userId,
                    email = "$userId@example.com",
                    email_normalized = "$userId@example.com",
                    password_hash = "phc",
                    role = UserRoleColumn.MEMBER.name,
                    display_name = userId,
                    status = UserStatusColumn.PENDING_APPROVAL.name,
                    created_at = 1L,
                    updated_at = 1L,
                    last_login_at = null,
                    can_edit = 1L,
                    can_share = 1L,
                    approved_by = null,
                    approved_at = null,
                    deleted_at = null,
                    invited_by = null,
                    tagline = null,
                    avatar_type = "auto",
                    timezone = "UTC",
                )
            }
        }

        fun SqlTestDatabases.grantRowsForUser(userId: String) =
            sql.collectionGrantsQueries
                .listActiveUserGrantsForPrincipal(principal_id = userId)
                .executeAsList()

        /** A [PushNotifier] whose [notifyWatch] violates the "MUST NOT throw" contract on purpose. */
        val throwingNotifier =
            object : PushNotifier {
                override suspend fun notify(
                    userId: String,
                    payload: PushPayload,
                ) = Unit

                override suspend fun notifyWatch(
                    kind: PushWatchKind,
                    key: String,
                    payload: PushPayload,
                ): Unit = error("relay boom")
            }

        test(
            "decidePendingRegistration(approve) survives a throwing push notifier: " +
                "eviction, the default grant, and the roster refresh all still run",
        ) {
            withSqlDatabase {
                val db = this
                sql.seedTestUser("root1", UserRoleColumn.ROOT)
                seedPendingUser("p1")

                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()
                val collectionRepository = CollectionRepository(sql, bus, syncRegistry, driver = driver)
                val grantRepository = CollectionGrantRepository(sql, bus, syncRegistry, driver = driver)
                val libraryRegistry = LibraryRegistry(sql)

                val grantIssuer =
                    DefaultAllBooksGrantIssuer(
                        collectionGrantRepository = grantRepository,
                        collectionRepository = collectionRepository,
                        libraryRegistry = libraryRegistry,
                        clock = fixedClock,
                    )

                val rosterRepo = AdminUserRosterRepository(sql, bus, syncRegistry, driver = driver)
                val maintainer = AdminUserRosterMaintainer(sql, rosterRepo)

                val settings = ServerSettingsRepository(sql, default = RegistrationPolicy.OPEN)
                val watchStore = PushWatchTokenStore(sql, fixedClock)

                runTest {
                    libraryRegistry.currentLibrary() // bootstrap ALL_BOOKS
                    maintainer.refresh("p1")
                    watchStore.register(PushWatchKind.REGISTRATION, "p1", "tok-1", PushPlatform.IOS)

                    val sessions =
                        SessionService(sql, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = fixedClock)
                    val svc =
                        AdminUserServiceImpl(
                            sql = sql,
                            sessions = sessions,
                            settings = settings,
                            registrationBroadcaster = RegistrationBroadcaster(),
                            registrationPolicyBroadcaster = RegistrationPolicyBroadcaster(),
                            bus = bus,
                            clock = fixedClock,
                            publicProfileMaintainer = sql.noOpPublicProfileMaintainer(),
                            defaultGrantIssuer = grantIssuer,
                            pushNotifier = throwingNotifier,
                            pushWatchTokens = watchStore,
                            adminUserRosterMaintainer = maintainer,
                            passwordResetService = testPasswordResetService(sql, fixedClock),
                        ).copyWith(principalFor("root1", UserRole.ROOT))

                    svc
                        .decidePendingRegistration(PendingRegistrationDecision(UserId("p1"), approved = true))
                        .shouldBeInstanceOf<AppResult.Success<*>>()

                    // Status flipped.
                    (svc.getUser(UserId("p1")) as AppResult.Success).data.status shouldBe UserStatus.ACTIVE

                    // Watch tokens evicted unconditionally — must not depend on the (doomed) push.
                    sql.pushWatchTokensQueries.countAll().executeAsOne() shouldBe 0L

                    // Default ALL_BOOKS grant still issued.
                    db.grantRowsForUser("p1").size shouldBe 1

                    // Admin roster refreshed to ACTIVE.
                    val row = rosterRepo.pullSince(userId = null, cursor = 0, limit = 100).items.single { it.id == "p1" }
                    row.status shouldBe "ACTIVE"
                }
            }
        }
    })
