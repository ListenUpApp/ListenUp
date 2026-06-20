@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.AdminUserPatch
import com.calypsan.listenup.api.dto.auth.PendingRegistrationDecision
import com.calypsan.listenup.api.dto.auth.PendingRegistrationOutcome
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserPermissions
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.auth.UserStatus
import com.calypsan.listenup.api.error.AdminError
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.dto.activity.ActivityType
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.RegistrationBroadcaster
import com.calypsan.listenup.server.auth.RegistrationDecision
import com.calypsan.listenup.server.auth.SessionService
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.ControlFrame
import com.calypsan.listenup.server.auth.RefreshTokenGenerator
import com.calypsan.listenup.server.auth.RefreshTokenHasher
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.db.UserEntity
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.db.UserStatusColumn
import com.calypsan.listenup.server.services.ActivityRecorder
import com.calypsan.listenup.server.services.ActivityRepository
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.asSqlDatabase
import com.calypsan.listenup.server.testing.noOpPublicProfileMaintainer
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import app.cash.turbine.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Instant

/**
 * Integration tests for [AdminUserServiceImpl].
 *
 * Real in-memory Flyway-migrated SQLite + real [SessionService] and
 * [ServerSettingsRepository]; no mocks. The acting caller is supplied via a
 * [PrincipalProvider] stub; [actAs] rebinds the service to a chosen
 * `(userId, role)` so a single test can exercise multiple callers.
 */
class AdminUserServiceImplTest :
    FunSpec({

        val pepper = "x".repeat(32).toByteArray()
        val fixedClock = FixedClock(Instant.fromEpochMilliseconds(1_700_000_000_000L))

        fun principalFor(
            userId: String,
            role: UserRole = UserRole.MEMBER,
        ): PrincipalProvider =
            PrincipalProvider {
                UserPrincipal(UserId(userId), SessionId("session-$userId"), role)
            }

        fun makeAdminUserService(
            db: Database,
            broadcaster: RegistrationBroadcaster = RegistrationBroadcaster(),
            bus: ChangeBus = ChangeBus(),
            activityRecorder: ActivityRecorder? = null,
        ): AdminUserServiceImpl {
            val sessions =
                SessionService(db.asSqlDatabase(), RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = fixedClock)
            val settings = ServerSettingsRepository(db, default = RegistrationPolicy.OPEN)
            return AdminUserServiceImpl(
                db = db,
                sessions = sessions,
                settings = settings,
                clock = fixedClock,
                registrationBroadcaster = broadcaster,
                bus = bus,
                publicProfileMaintainer = db.noOpPublicProfileMaintainer(),
                activityRecorder = activityRecorder,
            )
        }

        fun AdminUserServiceImpl.actAs(
            userId: String,
            role: UserRole = UserRole.MEMBER,
        ): AdminUserServiceImpl = copyWith(principalFor(userId, role))

        /** Seeds a user with an explicit status (the shared fixture only seeds ACTIVE). */
        fun Database.seedUserWithStatus(
            userId: String,
            role: UserRoleColumn = UserRoleColumn.MEMBER,
            userStatus: UserStatusColumn = UserStatusColumn.ACTIVE,
        ) {
            transaction(this) {
                UserEntity.new(userId) {
                    email = "$userId@example.com"
                    emailNormalized = "$userId@example.com"
                    passwordHash = "phc"
                    this.role = role
                    displayName = userId
                    status = userStatus
                    createdAt = 1L
                    updatedAt = 1L
                }
            }
        }

        // ── updateUser ──────────────────────────────────────────────────────────

        test("updateUser changes role + permissions for an admin caller") {
            withInMemoryDatabase {
                val db = this
                seedTestUser("root1", UserRoleColumn.ROOT)
                seedTestUser("m1", UserRoleColumn.MEMBER)
                runTest {
                    val svc = makeAdminUserService(db).actAs("root1", UserRole.ROOT)
                    val res =
                        svc.updateUser(
                            UserId("m1"),
                            AdminUserPatch(
                                role = UserRole.ADMIN,
                                permissions = UserPermissions(canEdit = false, canShare = false),
                            ),
                        )
                    val user = res.shouldSucceed()
                    user.role shouldBe UserRole.ADMIN
                    user.permissions shouldBe UserPermissions(canEdit = false, canShare = false)
                }
            }
        }

        test("updateUser patches only the supplied fields, leaving the rest unchanged") {
            withInMemoryDatabase {
                val db = this
                seedTestUser("root1", UserRoleColumn.ROOT)
                seedTestUser("m1", UserRoleColumn.MEMBER)
                runTest {
                    val svc = makeAdminUserService(db).actAs("root1", UserRole.ROOT)
                    val user = svc.updateUser(UserId("m1"), AdminUserPatch(displayName = "Renamed")).shouldSucceed()
                    user.displayName shouldBe "Renamed"
                    user.role shouldBe UserRole.MEMBER
                    user.permissions shouldBe UserPermissions(canEdit = true, canShare = true)
                }
            }
        }

        test("updateUser cannot change the root account's role") {
            withInMemoryDatabase {
                val db = this
                seedTestUser("root1", UserRoleColumn.ROOT)
                runTest {
                    val svc = makeAdminUserService(db).actAs("root1", UserRole.ROOT)
                    svc
                        .updateUser(UserId("root1"), AdminUserPatch(role = UserRole.MEMBER))
                        .shouldFail<AdminError.CannotModifyRoot>()
                }
            }
        }

        test("updateUser cannot demote the last admin") {
            withInMemoryDatabase {
                val db = this
                // Only one admin exists (a1); root is also an admin, so demoting a1 while
                // root remains is allowed — instead delete root scenario is separate.
                // Seed a single ADMIN with no ROOT so a1 is genuinely the last admin.
                seedTestUser("a1", UserRoleColumn.ADMIN)
                runTest {
                    val svc = makeAdminUserService(db).actAs("a1", UserRole.ADMIN)
                    svc
                        .updateUser(UserId("a1"), AdminUserPatch(role = UserRole.MEMBER))
                        .shouldFail<AdminError.CannotDemoteLastAdmin>()
                }
            }
        }

        test("updateUser by a non-admin is rejected with PermissionDenied") {
            withInMemoryDatabase {
                val db = this
                seedTestUser("root1", UserRoleColumn.ROOT)
                seedTestUser("m1", UserRoleColumn.MEMBER)
                runTest {
                    val svc = makeAdminUserService(db).actAs("m1", UserRole.MEMBER)
                    svc
                        .updateUser(UserId("root1"), AdminUserPatch(displayName = "x"))
                        .shouldFail<AuthError.PermissionDenied>()
                }
            }
        }

        // ── deleteUser ──────────────────────────────────────────────────────────

        test("deleteUser soft-deletes, revokes sessions, and rejects self/root/last-admin") {
            withInMemoryDatabase {
                val db = this
                seedTestUser("root1", UserRoleColumn.ROOT)
                seedTestUser("a1", UserRoleColumn.ADMIN)
                seedTestUser("m1", UserRoleColumn.MEMBER)
                runTest {
                    val sessions =
                        SessionService(db.asSqlDatabase(), RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = fixedClock)
                    val settings = ServerSettingsRepository(db, default = RegistrationPolicy.OPEN)
                    val svc =
                        AdminUserServiceImpl(
                            db = db,
                            sessions = sessions,
                            settings = settings,
                            clock = fixedClock,
                            registrationBroadcaster = RegistrationBroadcaster(),
                            bus = ChangeBus(),
                            publicProfileMaintainer = db.noOpPublicProfileMaintainer(),
                        ).copyWith(principalFor("a1", UserRole.ADMIN))

                    // m1 has an active session that must be revoked on delete.
                    val m1Session = sessions.createSession(UserId("m1"), label = "phone")

                    svc.deleteUser(UserId("a1")).shouldFail<AdminError.CannotDeleteSelf>()
                    svc.deleteUser(UserId("root1")).shouldFail<AdminError.CannotModifyRoot>()

                    svc.deleteUser(UserId("m1")).shouldSucceed()

                    // soft-deleted: excluded from listUsers
                    val listed = svc.listUsers().shouldSucceed().map { it.id.value }
                    listed shouldNotContain "m1"
                    listed shouldContain "a1"

                    // sessions revoked: rotate on m1's refresh token now fails.
                    sessions.rotate(m1Session.refreshToken) shouldBe null
                }
            }
        }

        test("deleteUser allows removing a non-last admin (root still counts)") {
            withInMemoryDatabase {
                val db = this
                seedTestUser("root1", UserRoleColumn.ROOT)
                seedTestUser("a1", UserRoleColumn.ADMIN)
                runTest {
                    // root deletes the only non-root admin → root still counts, so allowed.
                    val svc = makeAdminUserService(db).actAs("root1", UserRole.ROOT)
                    svc.deleteUser(UserId("a1")).shouldSucceed()
                    svc.listUsers().shouldSucceed().map { it.id.value } shouldNotContain "a1"
                }
            }
        }

        test("deleteUser publishes UserDeleted on the control channel, targeted to the deleted user, before revoking") {
            withInMemoryDatabase {
                val db = this
                val bus = ChangeBus()
                seedTestUser("root1", UserRoleColumn.ROOT)
                seedTestUser("m1", UserRoleColumn.MEMBER)
                runTest {
                    val frames = mutableListOf<ControlFrame>()
                    val job = launch { bus.subscribeControl().collect { frames += it } }
                    advanceUntilIdle() // subscriber live before the act (replay=0 control channel)
                    val svc = makeAdminUserService(db, bus = bus).actAs("root1", UserRole.ROOT)
                    svc.deleteUser(UserId("m1")).shouldSucceed()
                    advanceUntilIdle()
                    frames.any { it.userId == "m1" && it.control is SyncControl.UserDeleted } shouldBe true
                    job.cancel()
                }
            }
        }

        test("deleteUser failure does NOT publish UserDeleted") {
            withInMemoryDatabase {
                val db = this
                val bus = ChangeBus()
                seedTestUser("root1", UserRoleColumn.ROOT)
                seedTestUser("a1", UserRoleColumn.ADMIN)
                runTest {
                    val frames = mutableListOf<ControlFrame>()
                    val job = launch { bus.subscribeControl().collect { frames += it } }
                    advanceUntilIdle()
                    // Deleting ROOT is rejected → Failure → no UserDeleted frame.
                    val svc = makeAdminUserService(db, bus = bus).actAs("a1", UserRole.ADMIN)
                    svc.deleteUser(UserId("root1")).shouldFail<AdminError.CannotModifyRoot>()
                    advanceUntilIdle()
                    frames.any { it.control is SyncControl.UserDeleted } shouldBe false
                    job.cancel()
                }
            }
        }

        // ── getUser / searchUsers ─────────────────────────────────────────────────

        test("getUser returns the user; missing user fails UserNotFound") {
            withInMemoryDatabase {
                val db = this
                seedTestUser("root1", UserRoleColumn.ROOT)
                seedTestUser("m1", UserRoleColumn.MEMBER)
                runTest {
                    val svc = makeAdminUserService(db).actAs("root1", UserRole.ROOT)
                    svc.getUser(UserId("m1")).shouldSucceed().id shouldBe UserId("m1")
                    svc.getUser(UserId("ghost")).shouldFail<AdminError.UserNotFound>()
                }
            }
        }

        test("searchUsers matches display name and email case-insensitively, excluding deleted") {
            withInMemoryDatabase {
                val db = this
                seedTestUser("root1", UserRoleColumn.ROOT)
                seedTestUser("alice", UserRoleColumn.MEMBER)
                seedTestUser("bob", UserRoleColumn.MEMBER)
                runTest {
                    val svc = makeAdminUserService(db).actAs("root1", UserRole.ROOT)
                    val byName = svc.searchUsers("ALI").shouldSucceed().map { it.id.value }
                    byName shouldContain "alice"
                    byName shouldNotContain "bob"

                    svc.deleteUser(UserId("alice")).shouldSucceed()
                    svc.searchUsers("ali").shouldSucceed().map { it.id.value } shouldNotContain "alice"
                }
            }
        }

        test("searchUsers returns only ACTIVE members, excluding pending and denied registrations") {
            withInMemoryDatabase {
                val db = this
                seedTestUser("root1", UserRoleColumn.ROOT)
                seedUserWithStatus("activeAlice", userStatus = UserStatusColumn.ACTIVE)
                seedUserWithStatus("pendingAlice", userStatus = UserStatusColumn.PENDING_APPROVAL)
                seedUserWithStatus("deniedAlice", userStatus = UserStatusColumn.DENIED)
                runTest {
                    val svc = makeAdminUserService(db).actAs("root1", UserRole.ROOT)
                    // Search is an active-roster operation — pending/denied have their own surfaces
                    // and must not leak in (#624, sibling of the listUsers filter).
                    val ids = svc.searchUsers("alice").shouldSucceed().map { it.id.value }
                    ids shouldContain "activeAlice"
                    ids shouldNotContain "pendingAlice"
                    ids shouldNotContain "deniedAlice"
                }
            }
        }

        test("listUsers returns only ACTIVE members, excluding pending and denied registrations") {
            withInMemoryDatabase {
                val db = this
                seedTestUser("root1", UserRoleColumn.ROOT)
                seedTestUser("m1", UserRoleColumn.MEMBER)
                seedUserWithStatus("p1", userStatus = UserStatusColumn.PENDING_APPROVAL)
                seedUserWithStatus("d1", userStatus = UserStatusColumn.DENIED)
                runTest {
                    val svc = makeAdminUserService(db).actAs("root1", UserRole.ROOT)
                    val ids = svc.listUsers().shouldSucceed().map { it.id.value }
                    ids shouldContain "root1"
                    ids shouldContain "m1"
                    ids shouldNotContain "p1"
                    ids shouldNotContain "d1"
                }
            }
        }

        // ── listPendingUsers ──────────────────────────────────────────────────────

        test("listPendingUsers returns only PENDING_APPROVAL users") {
            withInMemoryDatabase {
                val db = this
                seedTestUser("root1", UserRoleColumn.ROOT)
                seedUserWithStatus("p1", userStatus = UserStatusColumn.PENDING_APPROVAL)
                runTest {
                    val svc = makeAdminUserService(db).actAs("root1", UserRole.ROOT)
                    val pending = svc.listPendingUsers().shouldSucceed().map { it.id.value }
                    pending shouldContain "p1"
                    pending shouldNotContain "root1"
                }
            }
        }

        // ── registration policy ─────────────────────────────────────────────────

        test("setRegistrationPolicy persists and getRegistrationPolicy reads it back") {
            withInMemoryDatabase {
                val db = this
                seedTestUser("root1", UserRoleColumn.ROOT)
                runTest {
                    val svc = makeAdminUserService(db).actAs("root1", UserRole.ROOT)
                    svc.setRegistrationPolicy(RegistrationPolicy.CLOSED).shouldSucceed()
                    svc.getRegistrationPolicy().shouldSucceed() shouldBe RegistrationPolicy.CLOSED
                }
            }
        }

        test("getRegistrationPolicy by a non-admin is rejected") {
            withInMemoryDatabase {
                val db = this
                seedTestUser("m1", UserRoleColumn.MEMBER)
                runTest {
                    val svc = makeAdminUserService(db).actAs("m1", UserRole.MEMBER)
                    svc.getRegistrationPolicy().shouldFail<AuthError.PermissionDenied>()
                }
            }
        }

        // ── decidePendingRegistration (relocated from AuthServiceImpl) ─────────────

        test("decidePendingRegistration approves, activates, and stamps approvedBy/approvedAt") {
            withInMemoryDatabase {
                val db = this
                seedTestUser("root1", UserRoleColumn.ROOT)
                seedUserWithStatus("p1", userStatus = UserStatusColumn.PENDING_APPROVAL)
                runTest {
                    val svc = makeAdminUserService(db).actAs("root1", UserRole.ROOT)
                    svc
                        .decidePendingRegistration(PendingRegistrationDecision(UserId("p1"), approved = true))
                        .shouldSucceed() shouldBe PendingRegistrationOutcome.Approved

                    val approved = svc.getUser(UserId("p1")).shouldSucceed()
                    approved.status shouldBe UserStatus.ACTIVE
                    approved.approvedBy shouldBe "root1"
                    approved.approvedAt shouldBe fixedClock.now().toEpochMilliseconds()
                }
            }
        }

        test("decidePendingRegistration(approve) records one user_joined for the approved user") {
            withInMemoryDatabase {
                val db = this
                val activities = ActivityRepository(db = db.asSqlDatabase())
                seedTestUser("root1", UserRoleColumn.ROOT)
                seedUserWithStatus("p1", userStatus = UserStatusColumn.PENDING_APPROVAL)
                runTest {
                    val svc =
                        makeAdminUserService(db, activityRecorder = ActivityRecorder(repo = activities, bus = ChangeBus()))
                            .actAs("root1", UserRole.ROOT)
                    svc.decidePendingRegistration(PendingRegistrationDecision(UserId("p1"), approved = true)).shouldSucceed()

                    val joined = activities.page(before = null, limit = 50).filter { it.type == ActivityType.USER_JOINED }
                    joined shouldHaveSize 1
                    joined.single().userId shouldBe "p1"
                }
            }
        }

        test("decidePendingRegistration(deny) records no user_joined") {
            withInMemoryDatabase {
                val db = this
                val activities = ActivityRepository(db = db.asSqlDatabase())
                seedTestUser("root1", UserRoleColumn.ROOT)
                seedUserWithStatus("p1", userStatus = UserStatusColumn.PENDING_APPROVAL)
                runTest {
                    val svc =
                        makeAdminUserService(db, activityRecorder = ActivityRecorder(repo = activities, bus = ChangeBus()))
                            .actAs("root1", UserRole.ROOT)
                    svc.decidePendingRegistration(PendingRegistrationDecision(UserId("p1"), approved = false)).shouldSucceed()

                    activities
                        .page(before = null, limit = 50)
                        .filter { it.type == ActivityType.USER_JOINED }
                        .shouldHaveSize(0)
                }
            }
        }

        test("decidePendingRegistration denies and blocks the applicant") {
            withInMemoryDatabase {
                val db = this
                seedTestUser("root1", UserRoleColumn.ROOT)
                seedUserWithStatus("p1", userStatus = UserStatusColumn.PENDING_APPROVAL)
                runTest {
                    val svc = makeAdminUserService(db).actAs("root1", UserRole.ROOT)
                    svc
                        .decidePendingRegistration(PendingRegistrationDecision(UserId("p1"), approved = false))
                        .shouldSucceed() shouldBe PendingRegistrationOutcome.Denied
                    svc.getUser(UserId("p1")).shouldSucceed().status shouldBe UserStatus.DENIED
                }
            }
        }

        test("decidePendingRegistration without admin role errors PermissionDenied") {
            withInMemoryDatabase {
                val db = this
                seedUserWithStatus("p1", userStatus = UserStatusColumn.PENDING_APPROVAL)
                seedTestUser("m1", UserRoleColumn.MEMBER)
                runTest {
                    val svc = makeAdminUserService(db).actAs("m1", UserRole.MEMBER)
                    svc
                        .decidePendingRegistration(PendingRegistrationDecision(UserId("p1"), approved = true))
                        .shouldFail<AuthError.PermissionDenied>()
                }
            }
        }

        test("decidePendingRegistration without a principal errors SessionExpired") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val svc = makeAdminUserService(db) // unscoped: no principal
                    svc
                        .decidePendingRegistration(PendingRegistrationDecision(UserId("u"), approved = true))
                        .shouldFail<AuthError.SessionExpired>()
                }
            }
        }

        test("decidePendingRegistration on a non-pending target errors PermissionDenied") {
            withInMemoryDatabase {
                val db = this
                seedTestUser("root1", UserRoleColumn.ROOT)
                seedTestUser("active1", UserRoleColumn.MEMBER) // ACTIVE, not pending
                runTest {
                    val svc = makeAdminUserService(db).actAs("root1", UserRole.ROOT)
                    svc
                        .decidePendingRegistration(PendingRegistrationDecision(UserId("active1"), approved = true))
                        .shouldFail<AuthError.PermissionDenied>()
                    svc
                        .decidePendingRegistration(PendingRegistrationDecision(UserId("ghost"), approved = true))
                        .shouldFail<AuthError.PermissionDenied>()
                }
            }
        }

        test("decidePendingRegistration(approve) notifies the broadcaster Approved for that user") {
            withInMemoryDatabase {
                val db = this
                val broadcaster = RegistrationBroadcaster()
                seedTestUser("root1", UserRoleColumn.ROOT)
                seedUserWithStatus("p1", userStatus = UserStatusColumn.PENDING_APPROVAL)
                runTest {
                    val received = async { broadcaster.subscribe("p1").first() }
                    advanceUntilIdle()
                    val svc = makeAdminUserService(db, broadcaster = broadcaster).actAs("root1", UserRole.ROOT)
                    svc.decidePendingRegistration(PendingRegistrationDecision(UserId("p1"), approved = true))
                    received.await() shouldBe RegistrationDecision.Approved
                }
            }
        }

        test("decidePendingRegistration(deny) notifies Denied") {
            withInMemoryDatabase {
                val db = this
                val broadcaster = RegistrationBroadcaster()
                seedTestUser("root1", UserRoleColumn.ROOT)
                seedUserWithStatus("p1", userStatus = UserStatusColumn.PENDING_APPROVAL)
                runTest {
                    val received = async { broadcaster.subscribe("p1").first() }
                    advanceUntilIdle()
                    val svc = makeAdminUserService(db, broadcaster = broadcaster).actAs("root1", UserRole.ROOT)
                    svc.decidePendingRegistration(PendingRegistrationDecision(UserId("p1"), approved = false))
                    received.await() shouldBe RegistrationDecision.Denied(null)
                }
            }
        }

        test("decidePendingRegistration failure does NOT notify") {
            withInMemoryDatabase {
                val db = this
                val broadcaster = RegistrationBroadcaster()
                seedTestUser("root1", UserRoleColumn.ROOT)
                seedTestUser("active1", UserRoleColumn.MEMBER) // ACTIVE, not pending → Failure
                runTest {
                    broadcaster.subscribe("active1").test {
                        val svc = makeAdminUserService(db, broadcaster = broadcaster).actAs("root1", UserRole.ROOT)
                        svc
                            .decidePendingRegistration(PendingRegistrationDecision(UserId("active1"), approved = true))
                            .shouldFail<AuthError.PermissionDenied>()
                        expectNoEvents()
                    }
                }
            }
        }
    })

private fun <T> AppResult<T>.shouldSucceed(): T = shouldBeInstanceOf<AppResult.Success<T>>().data

private inline fun <reified E : AppError> AppResult<*>.shouldFail(): E =
    shouldBeInstanceOf<AppResult.Failure>()
        .error
        .shouldBeInstanceOf<E>()
