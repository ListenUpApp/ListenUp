package com.calypsan.listenup.server.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date

class JwtConfigurationTest :
    FunSpec({
        val clock = Clock.fixed(Instant.parse("2026-05-02T12:00:00Z"), ZoneOffset.UTC)
        val cfg =
            JwtConfiguration(
                secret = "x".repeat(32),
                issuer = "listenup",
                audience = "listenup-client",
                accessTokenTtl = Duration.ofMinutes(15),
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
            val expiringCfg = cfg.copy(accessTokenTtl = Duration.ofSeconds(-1))
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
            val noRoleToken =
                JWT
                    .create()
                    .withIssuer("listenup")
                    .withAudience("listenup-client")
                    .withSubject("u-1")
                    .withJWTId("s-1")
                    .withExpiresAt(Date.from(clock.instant().plusSeconds(60)))
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
                    .withExpiresAt(Date.from(clock.instant().plusSeconds(60)))
                    .sign(Algorithm.HMAC256("x".repeat(32)))
            shouldThrow<JwtVerificationException> { cfg.verify(badRoleToken) }
        }
    })
