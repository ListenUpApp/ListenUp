@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.server.auth

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
 * Raw claim bag returned by [HmacJwtCodec.verify]. Uses plain strings so the codec
 * stays in commonMain without a dependency on the `:contract` value-class wrappers.
 * Platform-specific wrappers (e.g. [JwtConfiguration] in jvmMain) convert to
 * strongly-typed domain values.
 */
data class RawTokenClaims(
    val sub: String,
    val jti: String,
    val role: String,
    val expiresAtMs: Long,
)

/**
 * Multiplatform HS256 JWT issuer + verifier. Uses only stdlib, kotlinx.serialization,
 * and cryptography-kotlin — no JVM or `:contract` types — so it compiles for both
 * jvm() and linuxX64().
 *
 * - `sub` = user id, `jti` = session id, `role` = user role name. Claims kept minimal.
 * - Signing input is `base64url(header).base64url(payload)` per RFC 7515 (standard JWT).
 * - Signature comparison is constant-time to prevent timing attacks.
 */
class HmacJwtCodec(
    private val secret: String,
    private val issuer: String,
    private val audience: String,
    private val accessTokenTtl: Duration = DEFAULT_ACCESS_TOKEN_TTL,
    private val clock: Clock = Clock.System,
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
        userId: String,
        sessionId: String,
        role: String,
    ): String {
        val nowSec = clock.now().epochSeconds
        val expSec = (clock.now() + accessTokenTtl).epochSeconds
        val payload =
            JwtPayload(
                iss = issuer,
                aud = audience,
                sub = userId,
                jti = sessionId,
                role = role,
                iat = nowSec,
                nbf = nowSec,
                exp = expSec,
            )
        val signingInput = "$HEADER_B64.${b64(JSON.encodeToString(JwtPayload.serializer(), payload).encodeToByteArray())}"
        return "$signingInput.${b64(sign(signingInput))}"
    }

    fun verify(token: String): RawTokenClaims {
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
        val role = payload.role ?: throw JwtVerificationException("missing or invalid role claim")
        return RawTokenClaims(
            sub = payload.sub ?: throw JwtVerificationException("missing sub"),
            jti = payload.jti ?: throw JwtVerificationException("missing jti"),
            role = role,
            expiresAtMs = exp * 1000,
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
        internal const val MIN_SECRET_BYTES = 32
        private val DEFAULT_ACCESS_TOKEN_TTL: Duration = 15.minutes
        private val URL_NO_PAD = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
        internal val HEADER_B64 = URL_NO_PAD.encode("""{"alg":"HS256","typ":"JWT"}""".encodeToByteArray())
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    }
}
