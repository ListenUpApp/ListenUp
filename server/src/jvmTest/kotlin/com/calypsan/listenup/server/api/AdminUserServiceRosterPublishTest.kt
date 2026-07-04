@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.AdminUserPatch
import com.calypsan.listenup.api.dto.auth.PendingRegistrationDecision
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
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
import com.calypsan.listenup.server.services.AdminUserRosterMaintainer
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.sync.AdminUserRosterRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.noOpPublicProfileMaintainer
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * Verifies [AdminUserServiceImpl] publishes `admin_user_roster` changes on its four
 * roster-affecting mutations — approve, deny, delete, and role change — via
 * [AdminUserRosterMaintainer]. Mirrors [com.calypsan.listenup.server.auth.AuthServiceRosterPublishTest]
 * and [InviteServiceRosterPublishTest]'s shape for the admin-lifecycle surface.
 */
class AdminUserServiceRosterPublishTest :
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

        fun SqlTestDatabases.makeAdminUserService(maintainer: AdminUserRosterMaintainer): AdminUserServiceImpl {
            val sessions = SessionService(sql, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = fixedClock)
            val settings = ServerSettingsRepository(sql, default = RegistrationPolicy.OPEN)
            return AdminUserServiceImpl(
                sql = sql,
                sessions = sessions,
                settings = settings,
                clock = fixedClock,
                registrationBroadcaster = RegistrationBroadcaster(),
                registrationPolicyBroadcaster = RegistrationPolicyBroadcaster(),
                bus = ChangeBus(),
                publicProfileMaintainer = sql.noOpPublicProfileMaintainer(),
                adminUserRosterMaintainer = maintainer,
            )
        }

        test("approving a pending registration flips the roster row's status to ACTIVE") {
            withSqlDatabase {
                sql.seedTestUser("root1", UserRoleColumn.ROOT)
                seedPendingUser("p1")
                val rosterRepo = AdminUserRosterRepository(sql, ChangeBus(), SyncRegistry(), driver = driver)
                val maintainer = AdminUserRosterMaintainer(sql, rosterRepo)

                runTest {
                    // Mirrors the real flow, where register() already published the PENDING row.
                    maintainer.refresh("p1")

                    val svc = makeAdminUserService(maintainer).copyWith(principalFor("root1", UserRole.ROOT))
                    svc
                        .decidePendingRegistration(PendingRegistrationDecision(UserId("p1"), approved = true))
                        .shouldBeInstanceOf<AppResult.Success<*>>()

                    val row =
                        rosterRepo.pullSince(userId = null, cursor = 0, limit = 100).items.single { it.id == "p1" }
                    row.status shouldBe "ACTIVE"
                }
            }
        }

        test("denying a pending registration tombstones the roster row") {
            withSqlDatabase {
                sql.seedTestUser("root1", UserRoleColumn.ROOT)
                seedPendingUser("p1")
                val rosterRepo = AdminUserRosterRepository(sql, ChangeBus(), SyncRegistry(), driver = driver)
                val maintainer = AdminUserRosterMaintainer(sql, rosterRepo)

                runTest {
                    maintainer.refresh("p1")

                    val svc = makeAdminUserService(maintainer).copyWith(principalFor("root1", UserRole.ROOT))
                    svc
                        .decidePendingRegistration(PendingRegistrationDecision(UserId("p1"), approved = false))
                        .shouldBeInstanceOf<AppResult.Success<*>>()

                    val row =
                        rosterRepo.pullSince(userId = null, cursor = 0, limit = 100).items.single { it.id == "p1" }
                    row.deletedAt.shouldNotBeNull()
                }
            }
        }

        test("deleteUser tombstones the roster row") {
            withSqlDatabase {
                sql.seedTestUser("root1", UserRoleColumn.ROOT)
                sql.seedTestUser("m1", UserRoleColumn.MEMBER)
                val rosterRepo = AdminUserRosterRepository(sql, ChangeBus(), SyncRegistry(), driver = driver)
                val maintainer = AdminUserRosterMaintainer(sql, rosterRepo)

                runTest {
                    maintainer.refresh("m1")

                    val svc = makeAdminUserService(maintainer).copyWith(principalFor("root1", UserRole.ROOT))
                    svc.deleteUser(UserId("m1")).shouldBeInstanceOf<AppResult.Success<*>>()

                    val row =
                        rosterRepo.pullSince(userId = null, cursor = 0, limit = 100).items.single { it.id == "m1" }
                    row.deletedAt.shouldNotBeNull()
                }
            }
        }

        test("updateUser role change refreshes the roster row's role") {
            withSqlDatabase {
                sql.seedTestUser("root1", UserRoleColumn.ROOT)
                sql.seedTestUser("m1", UserRoleColumn.MEMBER)
                val rosterRepo = AdminUserRosterRepository(sql, ChangeBus(), SyncRegistry(), driver = driver)
                val maintainer = AdminUserRosterMaintainer(sql, rosterRepo)

                runTest {
                    maintainer.refresh("m1")

                    val svc = makeAdminUserService(maintainer).copyWith(principalFor("root1", UserRole.ROOT))
                    svc
                        .updateUser(UserId("m1"), AdminUserPatch(role = UserRole.ADMIN))
                        .shouldBeInstanceOf<AppResult.Success<*>>()

                    val row =
                        rosterRepo.pullSince(userId = null, cursor = 0, limit = 100).items.single { it.id == "m1" }
                    row.role shouldBe "ADMIN"
                }
            }
        }
    })
