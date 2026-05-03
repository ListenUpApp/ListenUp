package com.calypsan.listenup.server.auth

import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.server.db.DatabaseConfig
import com.calypsan.listenup.server.db.DatabaseFactory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class AuthServiceImplTest :
    FunSpec({
        val pepper = "x".repeat(32).toByteArray()
        val clock = Clock.fixed(Instant.parse("2026-05-02T12:00:00Z"), ZoneOffset.UTC)

        fun newSvc(policy: RegistrationPolicy = RegistrationPolicy.OPEN): AuthServiceImpl {
            val tmp = Files.createTempFile("listenup-test-", ".db").toFile().apply { deleteOnExit() }
            val db = DatabaseFactory.init(DatabaseConfig("jdbc:sqlite:${tmp.absolutePath}"))
            val hasher = PasswordHasher()
            val sessions = SessionService(db, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = clock)
            val jwt = JwtConfiguration("x".repeat(32), "listenup", "listenup-client", Duration.ofMinutes(15), clock)
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
                val s = svc.setupRoot(RegisterRequest("alice@example.com", "x".repeat(8), "Alice"))
                s.user.role shouldBe UserRole.ROOT
                s.accessToken.value.shouldNotBeBlank()
            }
        }

        test("setupRoot errors when any user exists") {
            val svc = newSvc()
            runTest {
                svc.setupRoot(RegisterRequest("a@b", "x".repeat(8), "A"))
                shouldThrow<AuthException> {
                    svc.setupRoot(RegisterRequest("c@d", "x".repeat(8), "C"))
                }.error.shouldBeInstanceOf<AuthError.SetupAlreadyComplete>()
            }
        }

        test("register errors SetupRequired when the instance is empty") {
            val svc = newSvc()
            runTest {
                shouldThrow<AuthException> {
                    svc.register(RegisterRequest("a@b", "x".repeat(8), "A"))
                }.error.shouldBeInstanceOf<AuthError.SetupRequired>()
            }
        }

        test("register on an OPEN instance returns Authenticated") {
            val svc = newSvc()
            runTest {
                svc.setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root"))
                val out = svc.register(RegisterRequest("alice@x", "x".repeat(8), "Alice"))
                out.shouldBeInstanceOf<RegisterResult.Authenticated>()
                out.session.user.role shouldBe UserRole.MEMBER
            }
        }

        test("register on a CLOSED instance errors RegistrationDisabled") {
            val svc = newSvc(policy = RegistrationPolicy.CLOSED)
            runTest {
                svc.setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root"))
                shouldThrow<AuthException> {
                    svc.register(RegisterRequest("alice@x", "x".repeat(8), "Alice"))
                }.error.shouldBeInstanceOf<AuthError.RegistrationDisabled>()
            }
        }

        test("register on an APPROVAL_QUEUE instance returns PendingApproval") {
            val svc = newSvc(policy = RegistrationPolicy.APPROVAL_QUEUE)
            runTest {
                svc.setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root"))
                val out = svc.register(RegisterRequest("alice@x", "x".repeat(8), "Alice"))
                out shouldBe RegisterResult.PendingApproval
            }
        }

        test("register errors EmailAlreadyExists on duplicate normalized email") {
            val svc = newSvc()
            runTest {
                svc.setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root"))
                svc.register(RegisterRequest("alice@x", "x".repeat(8), "Alice"))
                shouldThrow<AuthException> {
                    svc.register(RegisterRequest("ALICE@X", "x".repeat(8), "Alice2"))
                }.error.shouldBeInstanceOf<AuthError.EmailAlreadyExists>()
            }
        }

        test("login matches case-insensitively against email_normalized") {
            val svc = newSvc()
            runTest {
                svc.setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root"))
                svc.register(RegisterRequest("Alice@Example.COM", "correctpassword", "Alice"))

                val s = svc.login(LoginRequest("alice@example.com", "correctpassword"))
                s.user.email shouldBe "Alice@Example.COM"
            }
        }

        test("login errors InvalidCredentials on unknown email") {
            val svc = newSvc()
            runTest {
                svc.setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root"))
                shouldThrow<AuthException> {
                    svc.login(LoginRequest("ghost@x", "x".repeat(8)))
                }.error.shouldBeInstanceOf<AuthError.InvalidCredentials>()
            }
        }

        test("login errors InvalidCredentials on wrong password") {
            val svc = newSvc()
            runTest {
                svc.setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root"))
                svc.register(RegisterRequest("alice@x", "x".repeat(8), "Alice"))
                shouldThrow<AuthException> {
                    svc.login(LoginRequest("alice@x", "wrong-password!"))
                }.error.shouldBeInstanceOf<AuthError.InvalidCredentials>()
            }
        }

        test("login errors InvalidCredentials on malformed email (no @)") {
            val svc = newSvc()
            runTest {
                svc.setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root"))
                shouldThrow<AuthException> {
                    svc.login(LoginRequest("not-an-email", "x".repeat(8)))
                }.error.shouldBeInstanceOf<AuthError.InvalidCredentials>()
            }
        }

        test("login errors PendingApproval against a PENDING_APPROVAL account") {
            val svc = newSvc(policy = RegistrationPolicy.APPROVAL_QUEUE)
            runTest {
                svc.setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root"))
                svc.register(RegisterRequest("alice@x", "x".repeat(8), "Alice"))
                shouldThrow<AuthException> {
                    svc.login(LoginRequest("alice@x", "x".repeat(8)))
                }.error.shouldBeInstanceOf<AuthError.PendingApproval>()
            }
        }
    })
