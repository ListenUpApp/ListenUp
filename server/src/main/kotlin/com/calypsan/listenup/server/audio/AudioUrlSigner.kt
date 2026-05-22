package com.calypsan.listenup.server.audio

import io.ktor.http.encodeURLParameter
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.text.Charsets
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Mints and verifies short-lived signed audio URLs.
 *
 * The audio route is NOT JWT-gated — native players cannot reliably set an
 * Authorization header on a media URL — so access is a per-file, time-boxed
 * HMAC signature instead.
 *
 * `playback/prepare` calls [signedQuery] to produce
 * `?u=<userId>&exp=<epochSec>&sig=<hex>`. The audio route calls [verify]. A
 * leaked URL grants ONE file to ONE user for [ttl] — far tighter than a full
 * access token in the query string.
 *
 * @param signingKey derived from the JWT secret so operators manage no new
 *   secret: `HMAC-SHA256(jwtSecret, "listenup-audio-url-v1")`.
 * @param ttl must outlast a listening session — a single audiobook file can be
 *   played for hours. Default 12h.
 */
class AudioUrlSigner(
    private val signingKey: ByteArray,
    private val ttl: Duration = 12.hours,
    private val clock: Clock = Clock.System,
) {
    /**
     * Returns `u=<userId>&exp=<epochSec>&sig=<hex>` for the audio URL of
     * `(userId, bookId, fileId)`. The `bookId` and `fileId` are path segments
     * owned by the route and are not repeated in the query string.
     */
    fun signedQuery(
        userId: String,
        bookId: String,
        fileId: String,
    ): String {
        val exp = (clock.now() + ttl).epochSeconds
        val sig = hmacHex(payload(userId, bookId, fileId, exp))
        return "u=${userId.encodeURLParameter()}&exp=$exp&sig=$sig"
    }

    /**
     * Returns true when [sig] is a valid, unexpired HMAC signature for the
     * given `(userId, bookId, fileId, exp)` tuple.
     */
    fun verify(
        userId: String,
        bookId: String,
        fileId: String,
        exp: Long,
        sig: String,
    ): Boolean {
        if (exp <= clock.now().epochSeconds) return false
        val expected = hmacHex(payload(userId, bookId, fileId, exp))
        val expectedBytes = hexToBytes(expected) ?: return false
        val actualBytes = hexToBytes(sig) ?: return false
        return MessageDigest.isEqual(expectedBytes, actualBytes)
    }

    private fun payload(
        userId: String,
        bookId: String,
        fileId: String,
        exp: Long,
    ) = "$userId|$bookId|$fileId|$exp"

    private fun hmacHex(message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(signingKey, "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).toHexString()
    }

    /**
     * Decodes a lowercase-hex string to bytes, or returns null when the string
     * is not valid hex (odd length, non-hex chars, wrong length for SHA-256).
     */
    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length != 64) return null // SHA-256 produces 32 bytes = 64 hex chars
        return try {
            hex.hexToByteArray()
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    companion object {
        /**
         * Derives the signing key from a JWT secret so operators manage no new
         * secret. The derivation uses HMAC-SHA256 itself as a KDF:
         * `HMAC-SHA256(jwtSecret.toByteArray(), "listenup-audio-url-v1")`.
         */
        fun deriveSigningKey(jwtSecret: String): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(jwtSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            return mac.doFinal("listenup-audio-url-v1".toByteArray(Charsets.UTF_8))
        }
    }
}
