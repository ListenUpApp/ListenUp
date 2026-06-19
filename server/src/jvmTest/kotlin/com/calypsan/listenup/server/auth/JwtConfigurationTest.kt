@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.server.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.server.testing.FixedClock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.Date
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class JwtConfigurationTest :
    FunSpec({
        val clock = FixedClock(Instant.parse("2026-05-02T12:00:00Z"))
        val cfg =
            JwtConfiguration(
                secret = "x".repeat(32),
                issuer = "listenup",
                audience = "listenup-client",
                accessTokenTtl = 15.minutes,
                clock = clock,
            )

        test("rejects a secret shorter than 32 bytes") {
            shouldThrow<IllegalArgumentException> {
                JwtConfiguration(secret = "short", issuer = "x", audience = "y")
            }
        }

        test("issue + verify round-trip yields the same principal") {
            val token =
                cfg.issue(
                    userId = UserId("u-1"),
                    sessionId = SessionId("s-1"),
                    role = UserRole.MEMBER,
                )
            val claims = cfg.verify(token)
            claims.userId shouldBe UserId("u-1")
            claims.sessionId shouldBe SessionId("s-1")
            claims.role shouldBe UserRole.MEMBER
        }

        test("verify rejects expired tokens") {
            val expiringCfg = cfg.copy(accessTokenTtl = (-1).seconds)
            val token = expiringCfg.issue(UserId("u-1"), SessionId("s-1"), UserRole.MEMBER)
            shouldThrow<JwtVerificationException> { cfg.verify(token) }
        }

        test("verify rejects tokens missing exp claim") {
            val noExpToken =
                JWT
                    .create()
                    .withIssuer("listenup")
                    .withAudience("listenup-client")
                    .withSubject("u-1")
                    .withJWTId("s-1")
                    .withClaim("role", "MEMBER")
                    // intentionally no .withExpiresAt(...)
                    .sign(Algorithm.HMAC256("x".repeat(32)))
            shouldThrow<JwtVerificationException> { cfg.verify(noExpToken) }
        }

        test("verify rejects token from different issuer") {
            val foreignCfg = cfg.copy(issuer = "other-server")
            val foreignToken = foreignCfg.issue(UserId("u-1"), SessionId("s-1"), UserRole.MEMBER)
            shouldThrow<JwtVerificationException> { cfg.verify(foreignToken) }
        }

        test("verify rejects malformed token strings") {
            shouldThrow<JwtVerificationException> { cfg.verify("not.a.jwt") }
            shouldThrow<JwtVerificationException> { cfg.verify("garbage") }
            shouldThrow<JwtVerificationException> { cfg.verify("") }
        }

        test("verify rejects tokens with missing or invalid role claim") {
            val expMs = (clock.now() + 60.seconds).toEpochMilliseconds()
            val noRoleToken =
                JWT
                    .create()
                    .withIssuer("listenup")
                    .withAudience("listenup-client")
                    .withSubject("u-1")
                    .withJWTId("s-1")
                    .withExpiresAt(Date(expMs))
                    .sign(Algorithm.HMAC256("x".repeat(32)))
            shouldThrow<JwtVerificationException> { cfg.verify(noRoleToken) }

            val badRoleToken =
                JWT
                    .create()
                    .withIssuer("listenup")
                    .withAudience("listenup-client")
                    .withSubject("u-1")
                    .withJWTId("s-1")
                    .withClaim("role", "NOT_A_REAL_ROLE")
                    .withExpiresAt(Date(expMs))
                    .sign(Algorithm.HMAC256("x".repeat(32)))
            shouldThrow<JwtVerificationException> { cfg.verify(badRoleToken) }
        }
    })
