@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.server.auth

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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

@Serializable
private data class JwtPayload(
    val iss: String? = null,
    val aud: String? = null,
    val sub: String? = null,
    val jti: String? = null,
    val role: String? = null,
    val iat: Long? = null,
    val nbf: Long? = null,
    val exp: Long? = null,
)

/**
 * Hand-rolled HS256 JWT issuer + verifier. Multiplatform: HMAC-SHA-256 via cryptography-kotlin,
 * base64url via kotlin stdlib, JSON via kotlinx.serialization.
 *
 * `sub` = user id, `jti` = session id, `role` = user role. Claims kept minimal.
 */
data class JwtConfiguration(
    val secret: String,
    val issuer: String,
    val audience: String,
    val accessTokenTtl: Duration = DEFAULT_ACCESS_TOKEN_TTL,
    val clock: Clock = Clock.System,
) {
    init {
        require(secret.encodeToByteArray().size >= MIN_SECRET_BYTES) {
            "JWT secret must be at least $MIN_SECRET_BYTES bytes"
        }
    }

    private val hmacKey: HMAC.Key =
        CryptographyProvider.Default
            .get(HMAC)
            .keyDecoder(SHA256)
            .decodeFromByteArrayBlocking(HMAC.Key.Format.RAW, secret.encodeToByteArray())

    fun issue(
        userId: UserId,
        sessionId: SessionId,
        role: UserRole,
    ): String {
        val nowSec = clock.now().epochSeconds
        val expSec = (clock.now() + accessTokenTtl).epochSeconds
        val payload =
            JwtPayload(
                iss = issuer,
                aud = audience,
                sub = userId.value,
                jti = sessionId.value,
                role = role.name,
                iat = nowSec,
                nbf = nowSec,
                exp = expSec,
            )
        val signingInput = "$HEADER_B64.${b64(JSON.encodeToString(JwtPayload.serializer(), payload).encodeToByteArray())}"
        return "$signingInput.${b64(sign(signingInput))}"
    }

    fun verify(token: String): AccessTokenClaims {
        val parts = token.split(".")
        if (parts.size != 3) throw JwtVerificationException("malformed token")
        val signingInput = "${parts[0]}.${parts[1]}"
        val providedSig = decodeB64OrReject(parts[2], "bad signature encoding")
        if (!constantTimeEquals(sign(signingInput), providedSig)) {
            throw JwtVerificationException("signature mismatch")
        }
        val payloadJson = decodeB64OrReject(parts[1], "bad payload encoding").decodeToString()
        val payload =
            runCatching { JSON.decodeFromString(JwtPayload.serializer(), payloadJson) }
                .getOrElse { throw JwtVerificationException("unparseable payload", it) }

        if (payload.iss != issuer) throw JwtVerificationException("issuer mismatch")
        if (payload.aud != audience) throw JwtVerificationException("audience mismatch")
        val exp = payload.exp ?: throw JwtVerificationException("missing exp claim")
        val nowSec = clock.now().epochSeconds
        if (nowSec >= exp) throw JwtVerificationException("token expired")
        payload.nbf?.let { if (nowSec < it) throw JwtVerificationException("token not yet valid") }
        val role =
            runCatching { UserRole.valueOf(payload.role ?: error("missing role")) }
                .getOrElse { throw JwtVerificationException("missing or invalid role claim", it) }
        return AccessTokenClaims(
            userId = UserId(payload.sub ?: throw JwtVerificationException("missing sub")),
            sessionId = SessionId(payload.jti ?: throw JwtVerificationException("missing jti")),
            role = role,
            expiresAt = exp * 1000,
        )
    }

    private fun sign(signingInput: String): ByteArray =
        hmacKey.signatureGenerator().generateSignatureBlocking(signingInput.encodeToByteArray())

    private fun b64(bytes: ByteArray): String = URL_NO_PAD.encode(bytes)

    private fun decodeB64OrReject(s: String, reason: String): ByteArray =
        runCatching { URL_NO_PAD.decode(s) }.getOrElse { throw JwtVerificationException(reason, it) }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    companion object {
        private const val MIN_SECRET_BYTES = 32
        private val DEFAULT_ACCESS_TOKEN_TTL: Duration = 15.minutes
        private val URL_NO_PAD = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
        private val HEADER_B64 = URL_NO_PAD.encode("""{"alg":"HS256","typ":"JWT"}""".encodeToByteArray())
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    }
}
