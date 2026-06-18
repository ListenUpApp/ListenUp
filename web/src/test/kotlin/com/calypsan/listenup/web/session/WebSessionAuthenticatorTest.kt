package com.calypsan.listenup.web.session

import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.User
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.auth.UserStatus
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.web.testing.FakeLoopbackAuthClient
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest

class WebSessionAuthenticatorTest :
    FunSpec({
        fun rotatedSession(suffix: String) =
            AuthSession(
                accessToken = AccessToken("access-$suffix"),
                accessTokenExpiresAt = 10_000L,
                refreshToken = RefreshToken("refresh-$suffix"),
                refreshTokenExpiresAt = 99_000L,
                sessionId = SessionId("s1"),
                user =
                    User(
                        id = UserId("u1"),
                        email = "a@x",
                        displayName = "A",
                        role = UserRole.MEMBER,
                        status = UserStatus.ACTIVE,
                        createdAt = 0L,
                    ),
            )

        fun expiredSession() =
            WebSession(
                sessionId = SessionId("s1"),
                userId = UserId("u1"),
                role = UserRole.MEMBER,
                accessToken = AccessToken("stale"),
                refreshToken = RefreshToken("rt0"),
                accessExpiresAt = 0L,
            )

        test("a fresh access token is returned without refreshing") {
            val loopback = FakeLoopbackAuthClient()
            val authenticator = WebSessionAuthenticator(loopback, clock = { 1_000L }, skewMs = 0L)
            val session = expiredSession().apply { accessExpiresAt = 1_000_000L }

            authenticator.freshAccessToken(session) shouldBe AccessToken("stale")
            loopback.refreshCalls shouldBe 0
        }

        test("concurrent requests on an expired session trigger exactly one refresh") {
            val loopback =
                FakeLoopbackAuthClient().apply {
                    refreshResult = AppResult.Success(rotatedSession("v1"))
                    refreshDelayMs = 50L
                }
            // skewMs=0 so the freshness check after rotation (accessExpiresAt=10_000, clock=1_000)
            // correctly resolves to "fresh", letting the 7 waiting coroutines reuse the rotated token.
            val authenticator = WebSessionAuthenticator(loopback, clock = { 1_000L }, skewMs = 0L)
            val session = expiredSession()

            var tokens: List<AccessToken?> = emptyList()
            runTest {
                val deferred = (1..8).map { async { authenticator.freshAccessToken(session) } }
                tokens = deferred.awaitAll()
            }

            loopback.refreshCalls shouldBe 1
            tokens.forEach { it shouldBe AccessToken("access-v1") }
            session.refreshToken shouldBe RefreshToken("refresh-v1")
        }

        test("a failed refresh yields null and leaves the stored token unrotated") {
            val loopback =
                FakeLoopbackAuthClient().apply {
                    refreshResult = AppResult.Failure(AuthError.InvalidRefreshToken(familyRevoked = true))
                }
            val authenticator = WebSessionAuthenticator(loopback, clock = { 1_000L }, skewMs = 0L)
            val session = expiredSession()

            runTest { authenticator.freshAccessToken(session).shouldBeNull() }
            session.refreshToken shouldBe RefreshToken("rt0")
        }
    })
