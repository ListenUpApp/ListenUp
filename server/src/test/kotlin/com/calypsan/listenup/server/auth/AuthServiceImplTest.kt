@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.server.auth

import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.PendingRegistrationDecision
import com.calypsan.listenup.api.dto.auth.PendingRegistrationOutcome
import com.calypsan.listenup.api.dto.auth.RefreshRequest
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.db.DatabaseConfig
import com.calypsan.listenup.server.db.DatabaseFactory
import com.calypsan.listenup.server.db.UserEntity
import com.calypsan.listenup.server.db.UserTable
import com.calypsan.listenup.server.testing.FixedClock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.file.Files
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class AuthServiceImplTest :
    FunSpec({
        val pepper = "x".repeat(32).toByteArray()
        val clock = FixedClock(Instant.parse("2026-05-02T12:00:00Z"))

        fun newSvc(policy: RegistrationPolicy = RegistrationPolicy.OPEN): AuthServiceImpl {
            val tmp = Files.createTempFile("listenup-test-", ".db").toFile().apply { deleteOnExit() }
            val db = DatabaseFactory.init(DatabaseConfig("jdbc:sqlite:${tmp.absolutePath}"))
            val hasher = PasswordHasher()
            val sessions = SessionService(db, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = clock)
            val jwt = JwtConfiguration("x".repeat(32), "listenup", "listenup-client", 15.minutes, clock)
            return AuthServiceImpl(
                db = db,
                sessions = sessions,
                hasher = hasher,
                jwt = jwt,
                clock = clock,
                registrationPolicy = policy,
            )
        }

        test("setupRoot creates the root user when no users exist") {
            val svc = newSvc()
            runTest {
                val s = svc.setupRoot(RegisterRequest("alice@example.com", "x".repeat(8), "Alice")).shouldSucceed()
                s.user.role shouldBe UserRole.ROOT
                s.accessToken.value.shouldNotBeBlank()
            }
        }

        test("setupRoot errors when any user exists") {
            val svc = newSvc()
            runTest {
                svc.setupRoot(RegisterRequest("a@b", "x".repeat(8), "A")).shouldSucceed()
                svc
                    .setupRoot(RegisterRequest("c@d", "x".repeat(8), "C"))
                    .shouldFail<AuthError.SetupAlreadyComplete>()
            }
        }

        test("register errors SetupRequired when the instance is empty") {
            val svc = newSvc()
            runTest {
                svc
                    .register(RegisterRequest("a@b", "x".repeat(8), "A"))
                    .shouldFail<AuthError.SetupRequired>()
            }
        }

        test("register on an OPEN instance returns Authenticated") {
            val svc = newSvc()
            runTest {
                svc.setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root")).shouldSucceed()
                val out = svc.register(RegisterRequest("alice@x", "x".repeat(8), "Alice")).shouldSucceed()
                val authed = out.shouldBeInstanceOf<RegisterResult.Authenticated>()
                authed.session.user.role shouldBe UserRole.MEMBER
            }
        }

        test("register on a CLOSED instance errors RegistrationDisabled") {
            val svc = newSvc(policy = RegistrationPolicy.CLOSED)
            runTest {
                svc.setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root")).shouldSucceed()
                svc
                    .register(RegisterRequest("alice@x", "x".repeat(8), "Alice"))
                    .shouldFail<AuthError.RegistrationDisabled>()
            }
        }

        test("register on an APPROVAL_QUEUE instance returns PendingApproval with the new user id") {
            val svc = newSvc(policy = RegistrationPolicy.APPROVAL_QUEUE)
            runTest {
                svc.setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root")).shouldSucceed()
                val out = svc.register(RegisterRequest("alice@x", "x".repeat(8), "Alice")).shouldSucceed()
                val pending = out.shouldBeInstanceOf<RegisterResult.PendingApproval>()
                pending.userId.value
                    .isNotEmpty()
                    .shouldBe(true)
            }
        }

        test("register errors EmailAlreadyExists on duplicate normalized email") {
            val svc = newSvc()
            runTest {
                svc.setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root")).shouldSucceed()
                svc.register(RegisterRequest("alice@x", "x".repeat(8), "Alice")).shouldSucceed()
                svc
                    .register(RegisterRequest("ALICE@X", "x".repeat(8), "Alice2"))
                    .shouldFail<AuthError.EmailAlreadyExists>()
            }
        }

        test("login matches case-insensitively against email_normalized") {
            val svc = newSvc()
            runTest {
                svc.setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root")).shouldSucceed()
                svc.register(RegisterRequest("Alice@Example.COM", "correctpassword", "Alice")).shouldSucceed()

                val s = svc.login(LoginRequest("alice@example.com", "correctpassword")).shouldSucceed()
                s.user.email shouldBe "Alice@Example.COM"
            }
        }

        test("login errors InvalidCredentials on unknown email") {
            val svc = newSvc()
            runTest {
                svc.setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root")).shouldSucceed()
                svc
                    .login(LoginRequest("ghost@x", "x".repeat(8)))
                    .shouldFail<AuthError.InvalidCredentials>()
            }
        }

        test("login errors InvalidCredentials on wrong password") {
            val svc = newSvc()
            runTest {
                svc.setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root")).shouldSucceed()
                svc.register(RegisterRequest("alice@x", "x".repeat(8), "Alice")).shouldSucceed()
                svc
                    .login(LoginRequest("alice@x", "wrong-password!"))
                    .shouldFail<AuthError.InvalidCredentials>()
            }
        }

        test("login errors InvalidCredentials on malformed email (no @)") {
            val svc = newSvc()
            runTest {
                svc.setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root")).shouldSucceed()
                svc
                    .login(LoginRequest("not-an-email", "x".repeat(8)))
                    .shouldFail<AuthError.InvalidCredentials>()
            }
        }

        test("login errors PendingApproval against a PENDING_APPROVAL account") {
            val svc = newSvc(policy = RegistrationPolicy.APPROVAL_QUEUE)
            runTest {
                svc.setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root")).shouldSucceed()
                svc.register(RegisterRequest("alice@x", "x".repeat(8), "Alice")).shouldSucceed()
                svc
                    .login(LoginRequest("alice@x", "x".repeat(8)))
                    .shouldFail<AuthError.PendingApproval>()
            }
        }

        test("refreshSession rotates and returns a new token tied to the same session") {
            val svc = newSvc()
            runTest {
                svc.setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root")).shouldSucceed()
                val first =
                    svc
                        .register(RegisterRequest("alice@x", "x".repeat(8), "Alice"))
                        .shouldSucceed()
                        .shouldBeInstanceOf<RegisterResult.Authenticated>()

                val second = svc.refreshSession(RefreshRequest(first.session.refreshToken)).shouldSucceed()

                second.refreshToken.value shouldNotBe first.session.refreshToken.value
                second.sessionId shouldBe first.session.sessionId
                second.user.id shouldBe first.session.user.id
                second.accessToken.value.shouldNotBeBlank()
            }
        }

        test("refreshSession on an unknown token errors InvalidRefreshToken (familyRevoked=false)") {
            val svc = newSvc()
            runTest {
                svc.setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root")).shouldSucceed()
                val err =
                    svc
                        .refreshSession(RefreshRequest(RefreshToken("never-issued")))
                        .shouldFail<AuthError.InvalidRefreshToken>()
                err.familyRevoked shouldBe false
            }
        }

        test("refreshSession on a replayed token errors InvalidRefreshToken (familyRevoked=true)") {
            val svc = newSvc()
            runTest {
                svc.setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root")).shouldSucceed()
                val first =
                    svc
                        .register(RegisterRequest("alice@x", "x".repeat(8), "Alice"))
                        .shouldSucceed()
                        .shouldBeInstanceOf<RegisterResult.Authenticated>()
                val original = first.session.refreshToken

                svc.refreshSession(RefreshRequest(original)).shouldSucceed()

                val err =
                    svc
                        .refreshSession(RefreshRequest(original))
                        .shouldFail<AuthError.InvalidRefreshToken>()
                err.familyRevoked shouldBe true
            }
        }

        test("logout revokes only the caller's session") {
            val svc = newSvc()
            runTest {
                svc.setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root")).shouldSucceed()
                val authed =
                    svc
                        .register(RegisterRequest("alice@x", "x".repeat(8), "Alice"))
                        .shouldSucceed()
                        .shouldBeInstanceOf<RegisterResult.Authenticated>()
                val a = authed.session
                val b = svc.login(LoginRequest("alice@x", "x".repeat(8))).shouldSucceed()

                svc.copyWith(callerOf(a.user.id, a.sessionId)).logout().shouldSucceed()

                // a is dead — refresh fails with familyRevoked=false (revoked, not replayed).
                svc
                    .refreshSession(RefreshRequest(a.refreshToken))
                    .shouldFail<AuthError.InvalidRefreshToken>()
                    .familyRevoked shouldBe false

                // b is still alive — rotation succeeds.
                val refreshed = svc.refreshSession(RefreshRequest(b.refreshToken)).shouldSucceed()
                refreshed.sessionId shouldBe b.sessionId
            }
        }

        test("logout without a principal errors SessionExpired") {
            val svc = newSvc()
            runTest {
                svc.logout().shouldFail<AuthError.SessionExpired>()
            }
        }

        test("currentUser returns the principal's user") {
            val svc = newSvc()
            runTest {
                svc.setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root")).shouldSucceed()
                val authed =
                    svc
                        .register(RegisterRequest("alice@x", "x".repeat(8), "Alice"))
                        .shouldSucceed()
                        .shouldBeInstanceOf<RegisterResult.Authenticated>()

                val u =
                    svc
                        .copyWith(callerOf(authed.session.user.id, authed.session.sessionId))
                        .currentUser()
                        .shouldSucceed()

                u.id shouldBe authed.session.user.id
                u.email shouldBe "alice@x"
                u.displayName shouldBe "Alice"
            }
        }

        test("currentUser without a principal errors SessionExpired") {
            val svc = newSvc()
            runTest {
                svc.currentUser().shouldFail<AuthError.SessionExpired>()
            }
        }

        test("listSessions returns the caller's active sessions, marking the current one") {
            val svc = newSvc()
            runTest {
                svc.setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root")).shouldSucceed()
                val authed =
                    svc
                        .register(RegisterRequest("alice@x", "x".repeat(8), "Alice"))
                        .shouldSucceed()
                        .shouldBeInstanceOf<RegisterResult.Authenticated>()
                svc.login(LoginRequest("alice@x", "x".repeat(8))).shouldSucceed()

                val list =
                    svc
                        .copyWith(callerOf(authed.session.user.id, authed.session.sessionId))
                        .listSessions()
                        .shouldSucceed()

                list.size shouldBe 2
                list.count { it.current } shouldBe 1
                list.first { it.current }.id shouldBe authed.session.sessionId
            }
        }

        test("decidePendingRegistration approves and activates the target user") {
            val svc = newSvc(policy = RegistrationPolicy.APPROVAL_QUEUE)
            runTest {
                val root = svc.setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root")).shouldSucceed()
                svc.register(RegisterRequest("pending@x", "x".repeat(8), "Pending")).shouldSucceed()

                val pendingId = svc.findUserIdByEmail("pending@x")

                val outcome =
                    svc
                        .copyWith(callerOf(root.user.id, root.sessionId, role = UserRole.ROOT))
                        .decidePendingRegistration(PendingRegistrationDecision(pendingId, approved = true))
                        .shouldSucceed()

                outcome shouldBe PendingRegistrationOutcome.Approved

                // Applicant can now log in.
                val authed = svc.login(LoginRequest("pending@x", "x".repeat(8))).shouldSucceed()
                authed.user.role shouldBe UserRole.MEMBER
            }
        }

        test("decidePendingRegistration denies and blocks future logins") {
            val svc = newSvc(policy = RegistrationPolicy.APPROVAL_QUEUE)
            runTest {
                val root = svc.setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root")).shouldSucceed()
                svc.register(RegisterRequest("pending@x", "x".repeat(8), "Pending")).shouldSucceed()

                val pendingId = svc.findUserIdByEmail("pending@x")

                val outcome =
                    svc
                        .copyWith(callerOf(root.user.id, root.sessionId, role = UserRole.ROOT))
                        .decidePendingRegistration(PendingRegistrationDecision(pendingId, approved = false))
                        .shouldSucceed()

                outcome shouldBe PendingRegistrationOutcome.Denied

                svc
                    .login(LoginRequest("pending@x", "x".repeat(8)))
                    .shouldFail<AuthError.AccountDenied>()
            }
        }

        test("decidePendingRegistration without admin role errors PermissionDenied") {
            val svc = newSvc()
            runTest {
                svc.setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root")).shouldSucceed()
                val authed =
                    svc
                        .register(RegisterRequest("alice@x", "x".repeat(8), "Alice"))
                        .shouldSucceed()
                        .shouldBeInstanceOf<RegisterResult.Authenticated>()

                svc
                    .copyWith(callerOf(authed.session.user.id, authed.session.sessionId, role = UserRole.MEMBER))
                    .decidePendingRegistration(PendingRegistrationDecision(authed.session.user.id, approved = true))
                    .shouldFail<AuthError.PermissionDenied>()
            }
        }

        test("decidePendingRegistration without a principal errors SessionExpired") {
            val svc = newSvc()
            runTest {
                svc
                    .decidePendingRegistration(PendingRegistrationDecision(UserId("u"), approved = true))
                    .shouldFail<AuthError.SessionExpired>()
            }
        }

        test("decidePendingRegistration on a non-pending target errors PermissionDenied") {
            // Same wire shape as 'admin tried to act on a target outside their domain' —
            // we don't leak whether the user exists or what state they're in.
            val svc = newSvc()
            runTest {
                val root = svc.setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root")).shouldSucceed()
                val active =
                    svc
                        .register(RegisterRequest("alice@x", "x".repeat(8), "Alice"))
                        .shouldSucceed()
                        .shouldBeInstanceOf<RegisterResult.Authenticated>()

                svc
                    .copyWith(callerOf(root.user.id, root.sessionId, role = UserRole.ROOT))
                    .decidePendingRegistration(PendingRegistrationDecision(active.session.user.id, approved = true))
                    .shouldFail<AuthError.PermissionDenied>()

                svc
                    .copyWith(callerOf(root.user.id, root.sessionId, role = UserRole.ROOT))
                    .decidePendingRegistration(PendingRegistrationDecision(UserId("ghost"), approved = true))
                    .shouldFail<AuthError.PermissionDenied>()
            }
        }

        test("logoutAll revokes every session for the caller") {
            val svc = newSvc()
            runTest {
                svc.setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root")).shouldSucceed()
                val authed =
                    svc
                        .register(RegisterRequest("alice@x", "x".repeat(8), "Alice"))
                        .shouldSucceed()
                        .shouldBeInstanceOf<RegisterResult.Authenticated>()
                val a = authed.session
                val b = svc.login(LoginRequest("alice@x", "x".repeat(8))).shouldSucceed()

                svc.copyWith(callerOf(a.user.id, a.sessionId)).logoutAll().shouldSucceed()

                svc
                    .refreshSession(RefreshRequest(a.refreshToken))
                    .shouldFail<AuthError.InvalidRefreshToken>()
                svc
                    .refreshSession(RefreshRequest(b.refreshToken))
                    .shouldFail<AuthError.InvalidRefreshToken>()
            }
        }
    })

private fun callerOf(
    userId: UserId,
    sessionId: SessionId,
    role: UserRole = UserRole.MEMBER,
): PrincipalProvider =
    PrincipalProvider {
        UserPrincipal(userId = userId, sessionId = sessionId, role = role)
    }

private fun AuthServiceImpl.findUserIdByEmail(emailNormalized: String): UserId =
    transaction(db) {
        UserId(
            UserEntity
                .find { UserTable.emailNormalized eq emailNormalized }
                .single()
                .id.value,
        )
    }

/** Asserts the [AppResult] is a Success and returns the unwrapped value. */
private fun <T> AppResult<T>.shouldSucceed(): T = shouldBeInstanceOf<AppResult.Success<T>>().data

/**
 * Asserts the [AppResult] is a Failure carrying the requested [AppError]
 * subtype, returning it for further assertions.
 */
private inline fun <reified E : AppError> AppResult<*>.shouldFail(): E =
    shouldBeInstanceOf<AppResult.Failure>()
        .error
        .shouldBeInstanceOf<E>()
