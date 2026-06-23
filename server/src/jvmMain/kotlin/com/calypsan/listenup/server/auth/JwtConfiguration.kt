@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.server.auth

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime

data class AccessTokenClaims(
    val userId: UserId,
    val sessionId: SessionId,
    val role: UserRole,
    val expiresAt: Long, // unix millis
)

/**
 * JWT issuer + verifier for the JVM server. Delegates HS256 crypto to [HmacJwtCodec]
 * (commonMain) and maps between plain-string claims and `:contract` value-class types.
 *
 * `sub` = user id, `jti` = session id, `role` = user role.
 * No `email`, no `is_root`, no profile data — keep claims minimal.
 */
data class JwtConfiguration(
    val secret: String,
    val issuer: String,
    val audience: String,
    val accessTokenTtl: Duration = DEFAULT_ACCESS_TOKEN_TTL,
    val clock: Clock = Clock.System,
) {
    init {
        require(secret.encodeToByteArray().size >= HmacJwtCodec.MIN_SECRET_BYTES) {
            "JWT secret must be at least ${HmacJwtCodec.MIN_SECRET_BYTES} bytes"
        }
    }

    private val codec: HmacJwtCodec =
        HmacJwtCodec(
            secret = secret,
            issuer = issuer,
            audience = audience,
            accessTokenTtl = accessTokenTtl,
            clock = clock,
        )

    fun issue(
        userId: UserId,
        sessionId: SessionId,
        role: UserRole,
    ): String = codec.issue(userId.value, sessionId.value, role.name)

    fun verify(token: String): AccessTokenClaims {
        val raw = codec.verify(token)
        val role =
            runCatching { UserRole.valueOf(raw.role) }
                .getOrElse { throw JwtVerificationException("missing or invalid role claim", it) }
        return AccessTokenClaims(
            userId = UserId(raw.sub),
            sessionId = SessionId(raw.jti),
            role = role,
            expiresAt = raw.expiresAtMs,
        )
    }

    companion object {
        private val DEFAULT_ACCESS_TOKEN_TTL: Duration = 15.minutes
    }
}
