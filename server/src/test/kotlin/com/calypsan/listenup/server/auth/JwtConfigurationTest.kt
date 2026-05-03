package com.calypsan.listenup.server.auth

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
    })
