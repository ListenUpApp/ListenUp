@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.server.auth

import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.services.AdminUserRosterMaintainer
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.sync.AdminUserRosterRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Proves the `admin_user_roster` projection is published the moment a new user is created —
 * both an ACTIVE (OPEN policy) and a PENDING_APPROVAL (APPROVAL_QUEUE policy) registrant belong
 * in the admin roster, since the admin pending-approvals list reads from it — plus the initial
 * root user minted by [AuthServiceImpl.setupRoot].
 */
class AuthServiceRosterPublishTest :
    FunSpec({
        val pepper = "x".repeat(32).toByteArray()
        val clock = FixedClock(Instant.parse("2026-05-02T12:00:00Z"))

        test("register on an OPEN instance publishes an ACTIVE roster row") {
            withSqlDatabase {
                val rosterRepo = AdminUserRosterRepository(sql, ChangeBus(), SyncRegistry(), driver = driver)
                val maintainer = AdminUserRosterMaintainer(sql, rosterRepo)
                val svc = authService(sql, RegistrationPolicy.OPEN, clock, pepper, maintainer)

                runTest {
                    svc.setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root")).shouldSucceed()
                    val out = svc.register(RegisterRequest("alice@x", "x".repeat(8), "Alice")).shouldSucceed()
                    val authed = out.shouldBeInstanceOf<RegisterResult.Authenticated>()

                    val page = rosterRepo.pullSince(userId = null, cursor = 0, limit = 100)
                    val row = page.items.single { it.id == authed.session.user.id.value }
                    row.status shouldBe "ACTIVE"
                }
            }
        }

        test("register on an APPROVAL_QUEUE instance publishes a PENDING_APPROVAL roster row") {
            withSqlDatabase {
                val rosterRepo = AdminUserRosterRepository(sql, ChangeBus(), SyncRegistry(), driver = driver)
                val maintainer = AdminUserRosterMaintainer(sql, rosterRepo)
                val svc = authService(sql, RegistrationPolicy.APPROVAL_QUEUE, clock, pepper, maintainer)

                runTest {
                    svc.setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root")).shouldSucceed()
                    val out = svc.register(RegisterRequest("alice@x", "x".repeat(8), "Alice")).shouldSucceed()
                    val pending = out.shouldBeInstanceOf<RegisterResult.PendingApproval>()

                    val page = rosterRepo.pullSince(userId = null, cursor = 0, limit = 100)
                    val row = page.items.single { it.id == pending.userId.value }
                    row.status shouldBe "PENDING_APPROVAL"
                }
            }
        }

        test("setupRoot publishes a roster row for the root user") {
            withSqlDatabase {
                val rosterRepo = AdminUserRosterRepository(sql, ChangeBus(), SyncRegistry(), driver = driver)
                val maintainer = AdminUserRosterMaintainer(sql, rosterRepo)
                val svc = authService(sql, RegistrationPolicy.OPEN, clock, pepper, maintainer)

                runTest {
                    val session = svc.setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root")).shouldSucceed()

                    val page = rosterRepo.pullSince(userId = null, cursor = 0, limit = 100)
                    val row = page.items.single { it.id == session.user.id.value }
                    row.status shouldBe "ACTIVE"
                }
            }
        }
    })

@OptIn(ExperimentalTime::class)
private fun authService(
    db: ListenUpDatabase,
    policy: RegistrationPolicy,
    clock: FixedClock,
    pepper: ByteArray,
    adminUserRosterMaintainer: AdminUserRosterMaintainer,
): AuthServiceImpl {
    val hasher = PasswordHasher()
    val sessions = SessionService(db, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = clock)
    val jwt = JwtConfiguration("x".repeat(32), "listenup", "listenup-client", 15.minutes, clock)
    val settings = ServerSettingsRepository(db, default = policy)
    return AuthServiceImpl(
        db = db,
        sessions = sessions,
        hasher = Argon2Limiter(hasher),
        jwt = jwt,
        sessionIssuer = SessionIssuer(sessions, jwt, clock),
        clock = clock,
        settings = settings,
        adminUserRosterMaintainer = adminUserRosterMaintainer,
    )
}

/** Asserts the [AppResult] is a Success and returns the unwrapped value. */
private fun <T> AppResult<T>.shouldSucceed(): T = shouldBeInstanceOf<AppResult.Success<T>>().data
