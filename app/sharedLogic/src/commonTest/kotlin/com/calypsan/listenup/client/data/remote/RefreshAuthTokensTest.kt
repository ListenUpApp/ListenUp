package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.AuthSession as ContractAuthSession
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.User
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.auth.UserStatus
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.AuthSession
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

private fun fakeContractSession(
    access: String = "fresh-access",
    refresh: String = "fresh-refresh",
    sessionId: String = "session-1",
    userId: String = "user-1",
): ContractAuthSession =
    ContractAuthSession(
        accessToken = AccessToken(access),
        accessTokenExpiresAt = 0L,
        refreshToken = RefreshToken(refresh),
        refreshTokenExpiresAt = 0L,
        sessionId = SessionId(sessionId),
        user =
            User(
                id = UserId(userId),
                email = "alice@example.com",
                displayName = "Alice",
                role = UserRole.MEMBER,
                status = UserStatus.ACTIVE,
                createdAt = 0L,
            ),
    )

/**
 * Tests for [refreshAuthTokens] — the bridge between the Ktor bearer plugin's
 * `refreshTokens { }` block and `AuthRepository.refreshAccessToken()`.
 *
 * Coverage matrix:
 *  - Success → BearerTokens returned for the retry (persistence is the single-flight refresh's job, C1).
 *  - Failure(InvalidRefreshToken) and Failure(SessionExpired) → auth state cleared.
 *  - Failure(NetworkUnavailable) and other transient errors → auth state preserved.
 *  - CancellationException → re-thrown per coroutines convention.
 *  - Transport blowup → preserved + null returned.
 */
class RefreshAuthTokensTest :
    FunSpec({

        test("Success returns BearerTokens without re-persisting (persistence is the single-flight's job, C1)") {
            runTest {
                // saveAuthTokens is NOT stubbed — Mokkery throws if the bridge tries to persist, which
                // it must not: the rotated pair is already persisted inside AuthRepository's single-flight.
                val authSession = mock<AuthSession>()
                val session = fakeContractSession()

                val tokens = refreshAuthTokens(authSession) { AppResult.Success(session) }

                tokens.shouldNotBeNull()
                tokens.accessToken shouldBe "fresh-access"
                tokens.refreshToken shouldBe "fresh-refresh"
            }
        }

        test("Failure(InvalidRefreshToken) soft-clears session credentials and returns null") {
            runTest {
                val authSession = mock<AuthSession>()
                everySuspend { authSession.clearSessionCredentials() } returns Unit

                val tokens =
                    refreshAuthTokens(authSession) {
                        AppResult.Failure(AuthError.InvalidRefreshToken(familyRevoked = false))
                    }

                tokens.shouldBeNull()
                verifySuspend { authSession.clearSessionCredentials() }
            }
        }

        test("Failure(SessionExpired) — no stored refresh token — soft-clears session credentials") {
            runTest {
                val authSession = mock<AuthSession>()
                everySuspend { authSession.clearSessionCredentials() } returns Unit

                val tokens =
                    refreshAuthTokens(authSession) {
                        AppResult.Failure(AuthError.SessionExpired())
                    }

                tokens.shouldBeNull()
                verifySuspend { authSession.clearSessionCredentials() }
            }
        }

        test("Failure(InternalError) preserves auth state") {
            runTest {
                val authSession = mock<AuthSession>()
                // saveAuthTokens / clearAuthTokens not stubbed — Mokkery throws if called

                val tokens =
                    refreshAuthTokens(authSession) {
                        AppResult.Failure(InternalError())
                    }

                tokens.shouldBeNull()
            }
        }

        test("Failure(ValidationError) preserves auth state") {
            runTest {
                val authSession = mock<AuthSession>()

                val tokens =
                    refreshAuthTokens(authSession) {
                        AppResult.Failure(ValidationError("bad request"))
                    }

                tokens.shouldBeNull()
            }
        }

        test("CancellationException is re-thrown") {
            runTest {
                val authSession = mock<AuthSession>()
                var caught: CancellationException? = null
                try {
                    refreshAuthTokens(authSession) { throw CancellationException("test cancel") }
                } catch (e: CancellationException) {
                    caught = e
                }
                caught.shouldNotBeNull()
                caught.message shouldBe "test cancel"
            }
        }

        test("Generic transport exception preserves auth state and returns null") {
            runTest {
                val authSession = mock<AuthSession>()

                val tokens =
                    refreshAuthTokens(authSession) { error("boom") }

                tokens.shouldBeNull()
            }
        }
    })
