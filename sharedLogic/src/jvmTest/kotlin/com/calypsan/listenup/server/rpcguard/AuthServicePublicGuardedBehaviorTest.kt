package com.calypsan.listenup.server.rpcguard

import com.calypsan.listenup.api.AuthServicePublic
import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.User
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.auth.UserStatus
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.result.AppResult
import dev.mokkery.answering.returns
import dev.mokkery.answering.throws
import dev.mokkery.everySuspend
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

class AuthServicePublicGuardedBehaviorTest :
    FunSpec({

        val sampleLogin = LoginRequest(email = "u@example.com", password = "password123")
        val sampleSession =
            AuthSession(
                accessToken = AccessToken("access-token"),
                accessTokenExpiresAt = 1_000_000L,
                refreshToken = RefreshToken("refresh-token"),
                refreshTokenExpiresAt = 2_000_000L,
                sessionId = SessionId("session-id"),
                user =
                    User(
                        id = UserId("user-id"),
                        email = "u@example.com",
                        displayName = "Test User",
                        role = UserRole.MEMBER,
                        status = UserStatus.ACTIVE,
                        createdAt = 0L,
                    ),
            )

        test("Success passes through unchanged") {
            val delegate = mock<AuthServicePublic>()
            everySuspend { delegate.login(sampleLogin) } returns AppResult.Success(sampleSession)
            val guard = AuthServicePublicGuarded(delegate)

            runTest {
                guard.login(sampleLogin) shouldBe AppResult.Success(sampleSession)
            }
            verifySuspend { delegate.login(sampleLogin) }
        }

        test("typed Failure passes through unchanged") {
            val typed = AuthError.InvalidCredentials()
            val delegate = mock<AuthServicePublic>()
            everySuspend { delegate.login(sampleLogin) } returns AppResult.Failure(typed)
            val guard = AuthServicePublicGuarded(delegate)

            runTest {
                val result = guard.login(sampleLogin)
                result shouldBe AppResult.Failure(typed)
            }
        }

        test("escaped RuntimeException becomes a sanitized AppResult.Failure(InternalError)") {
            val delegate = mock<AuthServicePublic>()
            everySuspend { delegate.login(sampleLogin) } throws RuntimeException("boom")
            val guard = AuthServicePublicGuarded(delegate)

            runTest {
                val result = guard.login(sampleLogin)
                result.shouldBeInstanceOf<AppResult.Failure>()
                val err = result.error
                err.shouldBeInstanceOf<InternalError>()
                // Correlation id links the user's error to the server log line that holds the detail.
                err.correlationId shouldNotBe null
                err.correlationId!!.length shouldBe 36
                // The server exception's class name and message must NOT cross the wire — they can
                // embed SQL / paths / hostnames. Only the correlation id and the constant message ship.
                err.cause shouldBe null
                err.debugInfo shouldBe null
            }
        }

        test("CancellationException propagates without conversion") {
            val delegate = mock<AuthServicePublic>()
            everySuspend { delegate.login(sampleLogin) } throws CancellationException("stop")
            val guard = AuthServicePublicGuarded(delegate)

            runTest {
                shouldThrow<CancellationException> {
                    guard.login(sampleLogin)
                }
            }
        }
    })
