@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.server.auth

import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.DEVICE_FIELD_MAX
import com.calypsan.listenup.api.dto.auth.DeviceInfo
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.testing.migratedTestDatabase
import com.calypsan.listenup.server.testing.FixedClock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class AuthServiceDeviceTest :
    FunSpec({
        val pepper = "x".repeat(32).toByteArray()
        val clock = FixedClock(Instant.parse("2026-05-02T12:00:00Z"))

        fun newSvc(policy: RegistrationPolicy = RegistrationPolicy.OPEN): AuthServiceImpl {
            val db = migratedTestDatabase().db
            val hasher = PasswordHasher()
            val sessions =
                SessionService(db, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = clock)
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
            )
        }

        /** Register an ACTIVE member `u@x.co` / `password1` and return its userId. */
        suspend fun AuthServiceImpl.seedUser(): String {
            setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root")).shouldSucceed()
            val authed =
                register(RegisterRequest("u@x.co", "password1", "U"))
                    .shouldSucceed()
                    .shouldBeInstanceOf<RegisterResult.Authenticated>()
            return authed.session.user.id.value
        }

        test("login persists the request DeviceInfo onto the session") {
            val svc = newSvc()
            runTest {
                val userId = svc.seedUser()
                val login =
                    svc.login(
                        LoginRequest(
                            email = "u@x.co",
                            password = "password1",
                            deviceInfo =
                                DeviceInfo(
                                    platform = "Android",
                                    deviceModel = "Pixel 10",
                                    clientName = "ListenUp Android",
                                ),
                        ),
                    )
                login.shouldBeInstanceOf<AppResult.Success<AuthSession>>()
                val authed = svc.copyWith(callerOf(UserId(userId), login.data.sessionId))
                val sessions = (authed.listSessions() as AppResult.Success).data
                sessions.first { it.current }.deviceInfo?.deviceModel shouldBe "Pixel 10"
                sessions.first { it.current }.deviceInfo?.platform shouldBe "Android"
            }
        }

        test("withUserAgent captures the header onto the session") {
            val svc = newSvc()
            runTest {
                val userId = svc.seedUser()
                val login = svc.withUserAgent("ListenUp/9.9").login(LoginRequest(email = "u@x.co", password = "password1"))
                val authed = svc.copyWith(callerOf(UserId(userId), (login as AppResult.Success).data.sessionId))
                (authed.listSessions() as AppResult.Success).data.first { it.current }.userAgent shouldBe "ListenUp/9.9"
            }
        }

        test("listSessions tolerates a legacy row whose device field exceeds the 128-char limit") {
            val td = migratedTestDatabase()
            val db = td.db
            val sessions =
                SessionService(db, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = clock)
            val jwt = JwtConfiguration("x".repeat(32), "listenup", "listenup-client", 15.minutes, clock)
            val settings = ServerSettingsRepository(db, default = RegistrationPolicy.OPEN)
            val svc =
                AuthServiceImpl(
                    db = db,
                    sessions = sessions,
                    hasher = Argon2Limiter(PasswordHasher()),
                    jwt = jwt,
                    sessionIssuer = SessionIssuer(sessions, jwt, clock),
                    clock = clock,
                    settings = settings,
                )
            runTest {
                val userId = svc.seedUser()
                val login = svc.login(LoginRequest(email = "u@x.co", password = "password1")).shouldSucceed()

                // Simulate a legacy row written before DeviceInfo validation existed:
                // poke an over-long value straight into the column, bypassing the inbound guard.
                td.driver.execute(
                    identifier = null,
                    sql = "UPDATE sessions SET device_model = ? WHERE id = ?",
                    parameters = 2,
                ) {
                    bindString(0, "x".repeat(200))
                    bindString(1, login.sessionId.value)
                }

                val authed = svc.copyWith(callerOf(UserId(userId), login.sessionId))
                val list = authed.listSessions().shouldSucceed()

                list.first { it.current }.deviceInfo?.deviceModel shouldBe "x".repeat(DEVICE_FIELD_MAX)
            }
        }

        test("revokeSession revokes only the caller's own session; foreign id is a no-op Success") {
            val svc = newSvc()
            runTest {
                val userId = svc.seedUser()
                val s1 = svc.login(LoginRequest(email = "u@x.co", password = "password1")).shouldSucceed()
                val s2 = svc.login(LoginRequest(email = "u@x.co", password = "password1")).shouldSucceed()

                val authedAsS1 = svc.copyWith(callerOf(UserId(userId), s1.sessionId))
                authedAsS1.revokeSession(s2.sessionId).shouldSucceed()
                val remaining = authedAsS1.listSessions().shouldSucceed()
                remaining.none { it.id == s2.sessionId } shouldBe true

                // A different user can't revoke s1 — owner-scoped no-op Success leaves it live.
                val otherId = svc.seedUser2()
                val authedAsOther = svc.copyWith(callerOf(UserId(otherId), SessionId("other-session")))
                authedAsOther.revokeSession(s1.sessionId).shouldSucceed()
                svc.sessions.isLive(s1.sessionId) shouldBe true
            }
        }
    })

/** Register a SECOND active member `other@x.co` / `password1`; returns its userId. */
private suspend fun AuthServiceImpl.seedUser2(): String {
    val authed =
        register(RegisterRequest("other@x.co", "password1", "Other"))
            .shouldBeInstanceOf<AppResult.Success<RegisterResult>>()
            .data
            .shouldBeInstanceOf<RegisterResult.Authenticated>()
    return authed.session.user.id.value
}

private fun callerOf(
    userId: UserId,
    sessionId: SessionId,
    role: UserRole = UserRole.MEMBER,
): PrincipalProvider =
    PrincipalProvider {
        UserPrincipal(userId = userId, sessionId = sessionId, role = role)
    }

/** Asserts the [AppResult] is a Success and returns the unwrapped value. */
private fun <T> AppResult<T>.shouldSucceed(): T = shouldBeInstanceOf<AppResult.Success<T>>().data
