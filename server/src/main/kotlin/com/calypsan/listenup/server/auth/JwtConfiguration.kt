package com.calypsan.listenup.server.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import java.time.Clock
import java.time.Duration
import java.util.Date

data class AccessTokenClaims(
    val userId: UserId,
    val sessionId: SessionId,
    val role: UserRole,
    val expiresAt: Long, // unix millis
)

class JwtVerificationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * JWT issuer + verifier. HS256, secret loaded from env.
 *
 * `sub` = user id, `jti` = session id, `role` = user role.
 * No `email`, no `is_root`, no profile data — keep claims minimal.
 */
data class JwtConfiguration(
    val secret: String,
    val issuer: String,
    val audience: String,
    val accessTokenTtl: Duration = DEFAULT_ACCESS_TOKEN_TTL,
    val clock: Clock = Clock.systemUTC(),
) {
    init {
        require(secret.toByteArray(Charsets.UTF_8).size >= MIN_SECRET_BYTES) {
            "JWT secret must be at least $MIN_SECRET_BYTES bytes"
        }
    }

    private val algorithm: Algorithm = Algorithm.HMAC256(secret)

    /**
     * The auth0 verifier consults a [Clock] when checking `exp` / `nbf` / `iat`.
     * Wire our injected [Clock] in so tests with [Clock.fixed] are deterministic
     * — otherwise verification falls back to the system clock and any token
     * issued in the test's fixed past is rejected as expired by the wall clock.
     *
     * The clock-aware `build(Clock)` overload only exists on the concrete
     * [JWTVerifier.BaseVerification], not on the `Verification` interface that
     * [JWT.require] returns — hence the cast.
     */
    private val verifier: JWTVerifier =
        (
            JWT
                .require(algorithm)
                .withIssuer(issuer)
                .withAudience(audience) as JWTVerifier.BaseVerification
        ).build(clock)

    fun issue(
        userId: UserId,
        sessionId: SessionId,
        role: UserRole,
    ): String {
        val now = clock.instant()
        val exp = now.plus(accessTokenTtl)
        return JWT
            .create()
            .withIssuer(issuer)
            .withAudience(audience)
            .withSubject(userId.value)
            .withJWTId(sessionId.value)
            .withClaim(CLAIM_ROLE, role.name)
            .withIssuedAt(Date.from(now))
            .withNotBefore(Date.from(now))
            .withExpiresAt(Date.from(exp))
            .sign(algorithm)
    }

    fun verify(token: String): AccessTokenClaims {
        val decoded =
            try {
                verifier.verify(token)
            } catch (e: JWTVerificationException) {
                throw JwtVerificationException(e.message ?: "invalid token", e)
            }
        val role =
            runCatching { UserRole.valueOf(decoded.getClaim(CLAIM_ROLE).asString()) }
                .getOrElse { throw JwtVerificationException("missing or invalid role claim", it) }
        return AccessTokenClaims(
            userId = UserId(decoded.subject.orRejectAs("missing sub")),
            sessionId = SessionId(decoded.id.orRejectAs("missing jti")),
            role = role,
            expiresAt = decoded.expiresAt.time,
        )
    }

    private fun String?.orRejectAs(reason: String): String = this ?: throw JwtVerificationException(reason)

    companion object {
        private const val MIN_SECRET_BYTES = 32
        private const val CLAIM_ROLE = "role"
        private val DEFAULT_ACCESS_TOKEN_TTL: Duration = Duration.ofMinutes(15)
    }
}
