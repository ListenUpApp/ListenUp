package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.PendingRegistrationDecision
import com.calypsan.listenup.api.dto.auth.PendingRegistrationOutcome
import com.calypsan.listenup.api.dto.auth.PendingRegistrationToken
import com.calypsan.listenup.api.dto.auth.RefreshRequest
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.SessionSummary
import com.calypsan.listenup.api.dto.auth.User
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.auth.UserStatus
import com.calypsan.listenup.api.dto.auth.WeakPasswordReason
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.InternalError
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AuthDtoContractTest :
    FunSpec({

        test("identifier value classes round-trip through JSON") {
            roundTrip(UserId("u-1")) shouldBe UserId("u-1")
            roundTrip(SessionId("s-1")) shouldBe SessionId("s-1")
            roundTrip(AccessToken("at-1")) shouldBe AccessToken("at-1")
            roundTrip(RefreshToken("rt-1")) shouldBe RefreshToken("rt-1")
            roundTrip(PendingRegistrationToken("pt-1")) shouldBe PendingRegistrationToken("pt-1")
        }

        test("user enums round-trip through JSON") {
            UserRole.entries.forEach { roundTrip(it) shouldBe it }
            UserStatus.entries.forEach { roundTrip(it) shouldBe it }
            WeakPasswordReason.entries.forEach { roundTrip(it) shouldBe it }
        }

        test("User survives JSON round-trip") {
            val user =
                User(
                    id = UserId("u-1"),
                    email = "alice@example.com",
                    displayName = "Alice",
                    role = UserRole.MEMBER,
                    status = UserStatus.ACTIVE,
                    createdAt = 1714694400000L,
                )
            roundTrip(user) shouldBe user
        }

        test("SessionSummary survives JSON round-trip") {
            val s =
                SessionSummary(
                    id = SessionId("s-1"),
                    label = "My iPhone",
                    createdAt = 1L,
                    lastUsedAt = 2L,
                    current = true,
                )
            roundTrip(s) shouldBe s
        }

        test("LoginRequest enforces password length 8..1024") {
            LoginRequest(email = "a@b", password = "x".repeat(8))
            LoginRequest(email = "a@b", password = "x".repeat(1024))
            shouldThrow<IllegalArgumentException> {
                LoginRequest(email = "a@b", password = "x".repeat(7))
            }
            shouldThrow<IllegalArgumentException> {
                LoginRequest(email = "a@b", password = "x".repeat(1025))
            }
        }

        test("RegisterRequest enforces password length and non-blank displayName") {
            RegisterRequest(email = "a@b", password = "x".repeat(8), displayName = "Alice")
            shouldThrow<IllegalArgumentException> {
                RegisterRequest(email = "a@b", password = "x".repeat(8), displayName = "")
            }
            shouldThrow<IllegalArgumentException> {
                RegisterRequest(email = "a@b", password = "x".repeat(7), displayName = "Alice")
            }
        }

        test("auth requests round-trip through JSON") {
            val login =
                LoginRequest(
                    email = "a@b",
                    password = "x".repeat(8),
                    pendingRegistrationToken = PendingRegistrationToken("pt"),
                    sessionLabel = "phone",
                )
            val reg =
                RegisterRequest(
                    email = "a@b",
                    password = "x".repeat(8),
                    displayName = "Alice",
                    sessionLabel = null,
                )
            val refresh = RefreshRequest(refreshToken = RefreshToken("rt"))
            roundTrip(login) shouldBe login
            roundTrip(reg) shouldBe reg
            roundTrip(refresh) shouldBe refresh
        }

        test("AuthSession round-trips") {
            val s =
                AuthSession(
                    accessToken = AccessToken("at"),
                    accessTokenExpiresAt = 100,
                    refreshToken = RefreshToken("rt"),
                    refreshTokenExpiresAt = 200,
                    sessionId = SessionId("sid"),
                    user = User(UserId("u"), "a@b", "A", UserRole.MEMBER, UserStatus.ACTIVE, 1),
                )
            roundTrip(s) shouldBe s
        }

        test("RegisterResult variants round-trip polymorphically") {
            val authed: RegisterResult =
                RegisterResult.Authenticated(
                    AuthSession(
                        AccessToken("at"),
                        1,
                        RefreshToken("rt"),
                        2,
                        SessionId("sid"),
                        User(UserId("u"), "a@b", "A", UserRole.MEMBER, UserStatus.ACTIVE, 1),
                    ),
                )
            val pending: RegisterResult = RegisterResult.PendingApproval

            Json.decodeFromString<RegisterResult>(Json.encodeToString(authed)) shouldBe authed
            Json.decodeFromString<RegisterResult>(Json.encodeToString(pending)) shouldBe pending
        }

        test("PendingRegistrationOutcome variants round-trip") {
            val approved: PendingRegistrationOutcome =
                PendingRegistrationOutcome.Approved(PendingRegistrationToken("pt"))
            val denied: PendingRegistrationOutcome = PendingRegistrationOutcome.Denied
            Json.decodeFromString<PendingRegistrationOutcome>(Json.encodeToString(approved)) shouldBe approved
            Json.decodeFromString<PendingRegistrationOutcome>(Json.encodeToString(denied)) shouldBe denied
        }

        test("PendingRegistrationDecision round-trips") {
            val d = PendingRegistrationDecision(userId = UserId("u"), approved = true)
            roundTrip(d) shouldBe d
        }

        test("AuthService interface is reachable from commonTest") {
            // Compile-time check — referencing the type proves the @Rpc plugin emitted it.
            val klass: Any = AuthService::class
            klass.toString().contains("AuthService") shouldBe true
        }

        test("AppError.InternalError survives polymorphic round-trip") {
            val e: AppError = InternalError(correlationId = "c-1")
            Json.decodeFromString<AppError>(Json.encodeToString<AppError>(e)) shouldBe e
        }

        test("every AuthError variant survives polymorphic round-trip") {
            val variants: List<AuthError> =
                listOf(
                    AuthError.InvalidCredentials("c1"),
                    AuthError.EmailAlreadyExists("c2"),
                    AuthError.RegistrationDisabled("c3"),
                    AuthError.SetupRequired("c4"),
                    AuthError.SetupAlreadyComplete("c5"),
                    AuthError.PendingApproval("c6"),
                    AuthError.AccountDenied("c7"),
                    AuthError.SessionExpired("c8"),
                    AuthError.SessionNotFound("c9"),
                    AuthError.InvalidRefreshToken(familyRevoked = true, correlationId = "c10"),
                    AuthError.InvalidRefreshToken(familyRevoked = false, correlationId = "c11"),
                    AuthError.RateLimited(retryAfterSeconds = 30, correlationId = "c12"),
                    AuthError.WeakPassword(reason = WeakPasswordReason.TOO_SHORT, correlationId = "c13"),
                    AuthError.PermissionDenied("c14"),
                )
            variants.forEach { original ->
                val asAppError: AppError = original
                val json = Json.encodeToString<AppError>(asAppError)
                Json.decodeFromString<AppError>(json) shouldBe original
            }
        }
    })

private inline fun <reified T : Any> roundTrip(value: T): T = Json.decodeFromString<T>(Json.encodeToString(value))
