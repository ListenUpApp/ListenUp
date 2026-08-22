@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.PendingRegistrationDecision
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.notifications.NotificationEvent
import com.calypsan.listenup.api.notifications.NotificationPreference
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
import com.calypsan.listenup.server.notifications.NotificationEmitter
import com.calypsan.listenup.server.notifications.NotificationPrefsRepository
import com.calypsan.listenup.server.push.NoOpPushNotifier
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.notificationFixture
import com.calypsan.listenup.server.sync.notificationPayload
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.noOpPublicProfileMaintainer
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.testPasswordResetService
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant

/**
 * [AdminUserServiceImpl]'s notification-domain effects, split from [AdminUserServiceImplTest]
 * purely for spec size: a decided registration mints the applicant's `registration_decision`
 * inbox row (the durable half of the watch push, which [com.calypsan.listenup.server.push.RegistrationWatchTest]
 * pins), and a deleted user's notification rows and preference overrides are swept with them.
 */
class AdminUserServiceNotificationTest :
    FunSpec({

        val pepper = "x".repeat(32).toByteArray()
        val fixedClock = FixedClock(Instant.fromEpochMilliseconds(1_700_000_000_000L))

        fun principalFor(
            userId: String,
            role: UserRole,
        ): PrincipalProvider =
            PrincipalProvider {
                UserPrincipal(UserId(userId), SessionId("session-$userId"), role)
            }

        fun makeAdminUserService(
            db: SqlTestDatabases,
            notifications: NotificationEmitter? = null,
        ): AdminUserServiceImpl {
            val sessions =
                SessionService(db.sql, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = fixedClock)
            val settings = ServerSettingsRepository(db.sql, default = RegistrationPolicy.OPEN)
            return AdminUserServiceImpl(
                sql = db.sql,
                sessions = sessions,
                settings = settings,
                clock = fixedClock,
                registrationBroadcaster = RegistrationBroadcaster(),
                registrationPolicyBroadcaster = RegistrationPolicyBroadcaster(),
                bus = ChangeBus(),
                publicProfileMaintainer = db.sql.noOpPublicProfileMaintainer(),
                notifications = notifications,
                passwordResetService = testPasswordResetService(db.sql, fixedClock),
            )
        }

        /** Seeds a PENDING_APPROVAL user (the shared fixture only seeds ACTIVE). */
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

        test("approving a registration mints a registration_decision inbox row for the applicant") {
            withSqlDatabase {
                val db = this
                sql.seedTestUser("root1", UserRoleColumn.ROOT)
                seedPendingUser("p1")
                runTest {
                    val emitter =
                        NotificationEmitter(
                            db = sql,
                            repo = notificationFixture(),
                            prefs = NotificationPrefsRepository(sql),
                            notifier = NoOpPushNotifier(),
                        )
                    val svc =
                        makeAdminUserService(db, notifications = emitter)
                            .copyWith(principalFor("root1", UserRole.ROOT))

                    svc
                        .decidePendingRegistration(PendingRegistrationDecision(UserId("p1"), approved = true))
                        .shouldBeInstanceOf<AppResult.Success<*>>()

                    val ids = sql.notificationsQueries.selectLiveIdsForUserOldestFirst("p1", 10).executeAsList()
                    ids shouldHaveSize 1
                    val row = sql.notificationsQueries.selectById(ids.first()).executeAsOne()
                    row.type shouldBe "registration_decision"
                    contractJson
                        .decodeFromString(NotificationEvent.serializer(), row.payload)
                        .shouldBeInstanceOf<NotificationEvent.RegistrationDecision>()
                        .approved shouldBe true
                }
            }
        }

        test("deleteUser sweeps the deleted user's notification rows and preference overrides") {
            withSqlDatabase {
                val db = this
                sql.seedTestUser("root1", UserRoleColumn.ROOT)
                sql.seedTestUser("m1", UserRoleColumn.MEMBER)
                runTest {
                    notificationFixture().upsert(notificationPayload("n-m1"), userId = "m1")
                    NotificationPrefsRepository(sql)
                        .update("m1", "campfire_invite", NotificationPreference(inApp = false, push = false))

                    val svc = makeAdminUserService(db).copyWith(principalFor("root1", UserRole.ROOT))
                    svc.deleteUser(UserId("m1")).shouldBeInstanceOf<AppResult.Success<*>>()

                    sql.notificationsQueries.selectById("n-m1").executeAsOneOrNull() shouldBe null
                    sql.notificationPrefsQueries
                        .selectForUser("m1")
                        .executeAsList()
                        .shouldBeEmpty()
                }
            }
        }
    })
