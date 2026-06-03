package com.calypsan.listenup.api.result

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.User
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.auth.UserStatus
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.InternalError
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.encodeToString
import kotlinx.serialization.serializer

class AppResultContractTest :
    FunSpec({

        test("AppResult.Success<AuthSession> round-trips through JSON") {
            val session =
                AuthSession(
                    accessToken = AccessToken("at"),
                    accessTokenExpiresAt = 100L,
                    refreshToken = RefreshToken("rt"),
                    refreshTokenExpiresAt = 200L,
                    sessionId = SessionId("sid"),
                    user =
                        User(
                            id = UserId("u-1"),
                            email = "alice@x",
                            displayName = "Alice",
                            role = UserRole.MEMBER,
                            status = UserStatus.ACTIVE,
                            createdAt = 1L,
                        ),
                )
            val original: AppResult<AuthSession> = AppResult.Success(session)

            val json = contractJson.encodeToString(serializer<AppResult<AuthSession>>(), original)
            val decoded = contractJson.decodeFromString(serializer<AppResult<AuthSession>>(), json)

            decoded shouldBe original
        }

        test("AppResult.Failure<AuthSession> with typed AuthError round-trips") {
            val original: AppResult<AuthSession> =
                AppResult.Failure(AuthError.InvalidCredentials(correlationId = "corr-1"))

            val json = contractJson.encodeToString(serializer<AppResult<AuthSession>>(), original)
            val decoded = contractJson.decodeFromString(serializer<AppResult<AuthSession>>(), json)

            decoded shouldBe original
            val failure = decoded.shouldBeInstanceOf<AppResult.Failure>()
            failure.error.shouldBeInstanceOf<AuthError.InvalidCredentials>().correlationId shouldBe "corr-1"
        }

        test("AppResult.Failure with InternalError round-trips") {
            val original: AppResult<Unit> = AppResult.Failure(InternalError(correlationId = "corr-2"))

            val json = contractJson.encodeToString(serializer<AppResult<Unit>>(), original)
            val decoded = contractJson.decodeFromString(serializer<AppResult<Unit>>(), json)

            decoded shouldBe original
            decoded.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<InternalError>()
        }

        test("every AuthError variant survives wrapping in AppResult.Failure") {
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
                    AuthError.RateLimited(retryAfterSeconds = 30, correlationId = "c11"),
                    AuthError.PermissionDenied("c12"),
                )
            variants.forEach { err ->
                val original: AppResult<Unit> = AppResult.Failure(err)
                val json = contractJson.encodeToString(serializer<AppResult<Unit>>(), original)
                contractJson.decodeFromString(serializer<AppResult<Unit>>(), json) shouldBe original
            }
        }

        test("fold dispatches by variant exhaustively") {
            val s: AppResult<Int> = AppResult.Success(42)
            val f: AppResult<Int> = AppResult.Failure(AuthError.SessionExpired())

            s.fold(onSuccess = { it * 2 }, onFailure = { -1 }) shouldBe 84
            f.fold(onSuccess = { it * 2 }, onFailure = { -1 }) shouldBe -1
        }

        test("map preserves failure, transforms success") {
            val s: AppResult<Int> = AppResult.Success(10)
            val f: AppResult<Int> = AppResult.Failure(AuthError.SessionExpired())

            s.map { it.toString() } shouldBe AppResult.Success("10")
            f.map { it.toString() } shouldBe f
        }

        test("getOrNull / errorOrNull complement") {
            val s: AppResult<String> = AppResult.Success("hi")
            val f: AppResult<String> = AppResult.Failure(AuthError.InvalidCredentials())

            s.getOrNull() shouldBe "hi"
            s.errorOrNull() shouldBe null
            f.getOrNull() shouldBe null
            f.errorOrNull().shouldBeInstanceOf<AuthError.InvalidCredentials>()
        }

        test("isSuccess / isFailure smart-cast") {
            val s: AppResult<Int> = AppResult.Success(1)
            if (s.isSuccess()) {
                // Smart-cast to AppResult.Success<Int> — `data` is reachable.
                s.data shouldBe 1
            } else {
                error("expected success")
            }

            val f: AppResult<Int> = AppResult.Failure(AuthError.SessionExpired())
            if (f.isFailure()) {
                f.error.shouldBeInstanceOf<AuthError.SessionExpired>()
            } else {
                error("expected failure")
            }
        }
    })
