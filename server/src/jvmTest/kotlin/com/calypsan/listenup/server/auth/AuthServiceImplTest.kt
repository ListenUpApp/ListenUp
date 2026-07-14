@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.server.auth

import com.calypsan.listenup.api.dto.activity.ActivityType
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RefreshRequest
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.services.ActivityRecorder
import com.calypsan.listenup.server.services.ActivityRepository
import com.calypsan.listenup.server.services.ActivitySyncRepository
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.MutableClock
import com.calypsan.listenup.server.testing.migratedTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class AuthServiceImplTest :
    FunSpec({
        val pepper = "x".repeat(32).toByteArray()
        val clock = FixedClock(Instant.parse("2026-05-02T12:00:00Z"))

        fun newSvc(
            policy: RegistrationPolicy = RegistrationPolicy.OPEN,
            svcClock: Clock = clock,
        ): AuthServiceImpl {
            val db = migratedTestDatabase().db
            val hasher = PasswordHasher()
            val sessions =
                SessionService(db, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = svcClock)
            val jwt = JwtConfiguration("x".repeat(32), "listenup", "listenup-client", 15.minutes, svcClock)
            val settings = ServerSettingsRepository(db, default = policy)
            return AuthServiceImpl(
                db = db,
                sessions = sessions,
                hasher = Argon2Limiter(hasher),
                jwt = jwt,
                sessionIssuer = SessionIssuer(sessions, jwt, svcClock),
                clock = svcClock,
                settings = settings,
            )
        }

        // Builds a service wired to a real ActivityRecorder over the same DB, returning the
        // ActivityRepository so the test can read the recorded activities back.
        fun newSvcWithRecorder(
            policy: RegistrationPolicy = RegistrationPolicy.OPEN,
        ): Pair<AuthServiceImpl, ActivityRepository> {
            val (db, driver) = migratedTestDatabase()
            val hasher = PasswordHasher()
            val sessions =
                SessionService(db, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = clock)
            val jwt = JwtConfiguration("x".repeat(32), "listenup", "listenup-client", 15.minutes, clock)
            val settings = ServerSettingsRepository(db, default = policy)
            val activities = ActivityRepository(db = db)
            val svc =
                AuthServiceImpl(
                    db = db,
                    sessions = sessions,
                    hasher = Argon2Limiter(hasher),
                    jwt = jwt,
                    sessionIssuer = SessionIssuer(sessions, jwt, clock),
                    clock = clock,
                    settings = settings,
                    activityRecorder =
                        ActivityRecorder(
                            syncRepo =
                                ActivitySyncRepository(
                                    db = db,
                                    bus = ChangeBus(),
                                    registry = SyncRegistry(),
                                    driver = driver,
                                ),
                        ),
                )
            return svc to activities
        }

        test("setupRoot records one user_joined for the new root") {
            val (svc, activities) = newSvcWithRecorder()
            runTest {
                val s = svc.setupRoot(RegisterRequest("alice@example.com", "x".repeat(8), "Alice")).shouldSucceed()

                val joined = activities.page(before = null, limit = 50).filter { it.type == ActivityType.USER_JOINED }
                joined shouldHaveSize 1
                joined.single().userId shouldBe s.user.id.value
            }
        }

        test("register on an OPEN instance records one user_joined for the new member") {
            val (svc, activities) = newSvcWithRecorder()
            runTest {
                val root = svc.setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root")).shouldSucceed()
                val out = svc.register(RegisterRequest("alice@x", "x".repeat(8), "Alice")).shouldSucceed()
                val authed = out.shouldBeInstanceOf<RegisterResult.Authenticated>()

                val joined = activities.page(before = null, limit = 50).filter { it.type == ActivityType.USER_JOINED }
                // One for the root, one for the registered member.
                joined shouldHaveSize 2
                joined.map { it.userId }.toSet() shouldBe setOf(root.user.id.value, authed.session.user.id.value)
            }
        }

        test("register on an APPROVAL_QUEUE instance records no user_joined for the pending applicant") {
            val (svc, activities) = newSvcWithRecorder(policy = RegistrationPolicy.APPROVAL_QUEUE)
            runTest {
                svc.setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root")).shouldSucceed()
                svc.register(RegisterRequest("alice@x", "x".repeat(8), "Alice")).shouldSucceed()

                // Only the root joined; the pending applicant gets their user_joined at approval time.
                activities.page(before = null, limit = 50).filter { it.type == ActivityType.USER_JOINED } shouldHaveSize 1
            }
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

        test("login persists the device timezone on the user row") {
            val svc = newSvc()
            runTest {
                svc.setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root")).shouldSucceed()
                svc.register(RegisterRequest("alice@x", "x".repeat(8), "Alice")).shouldSucceed()

                val s = svc.login(LoginRequest("alice@x", "x".repeat(8), timezone = "Europe/London")).shouldSucceed()

                val storedTimezone =
                    svc.db.usersQueries
                        .selectTimezoneById(s.user.id.value)
                        .executeAsOne()
                storedTimezone shouldBe "Europe/London"
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

        test("login errors InvalidCredentials against a soft-deleted account") {
            val svc = newSvc()
            runTest {
                svc.setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root")).shouldSucceed()
                val authed =
                    svc
                        .register(RegisterRequest("alice@x", "x".repeat(8), "Alice"))
                        .shouldSucceed()
                        .shouldBeInstanceOf<RegisterResult.Authenticated>()

                // Soft-delete: stamp deletedAt directly, mirroring AdminUserServiceImpl.deleteUser.
                // status stays ACTIVE — the deletedAt check, not the status branch, must deny login.
                svc.db.usersQueries.markDeletedAt(
                    deleted_at = clock.now().toEpochMilliseconds(),
                    id = authed.session.user.id.value,
                )

                // Indistinguishable from a nonexistent account — no existence leak.
                svc
                    .login(LoginRequest("alice@x", "x".repeat(8)))
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

        test("refreshSession replaying a token AFTER the grace window errors InvalidRefreshToken (familyRevoked=true)") {
            val mutClock = MutableClock(Instant.parse("2026-05-02T12:00:00Z"))
            val svc = newSvc(svcClock = mutClock)
            runTest {
                svc.setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root")).shouldSucceed()
                val first =
                    svc
                        .register(RegisterRequest("alice@x", "x".repeat(8), "Alice"))
                        .shouldSucceed()
                        .shouldBeInstanceOf<RegisterResult.Authenticated>()
                val original = first.session.refreshToken

                svc.refreshSession(RefreshRequest(original)).shouldSucceed()

                // Past the lost-response grace window, replaying the original token is an
                // unambiguous reuse attack → family revoke.
                mutClock.instant = mutClock.instant + 61.seconds
                val err =
                    svc
                        .refreshSession(RefreshRequest(original))
                        .shouldFail<AuthError.InvalidRefreshToken>()
                err.familyRevoked shouldBe true
            }
        }

        test("refreshSession replaying a token WITHIN the grace window rotates again (lost-response retry, C4)") {
            val mutClock = MutableClock(Instant.parse("2026-05-02T12:00:00Z"))
            val svc = newSvc(svcClock = mutClock)
            runTest {
                svc.setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root")).shouldSucceed()
                val first =
                    svc
                        .register(RegisterRequest("alice@x", "x".repeat(8), "Alice"))
                        .shouldSucceed()
                        .shouldBeInstanceOf<RegisterResult.Authenticated>()
                val original = first.session.refreshToken

                val firstRotation = svc.refreshSession(RefreshRequest(original)).shouldSucceed()

                // The client never saw firstRotation's response and re-presents the original token a
                // moment later — a dropped-response retry, not an attack.
                mutClock.instant = mutClock.instant + 30.seconds
                val retry = svc.refreshSession(RefreshRequest(original)).shouldSucceed()
                retry.sessionId shouldBe firstRotation.sessionId
                retry.refreshToken shouldNotBe firstRotation.refreshToken
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
