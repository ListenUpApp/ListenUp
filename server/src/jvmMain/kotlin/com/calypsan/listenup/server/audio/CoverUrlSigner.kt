package com.calypsan.listenup.server.audio

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import io.ktor.http.encodeURLParameter
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Mints and verifies short-lived signed cover URLs.
 *
 * The cover route is NOT JWT-gated — Cast and native players cannot reliably
 * set an Authorization header on a media URL — so access is a per-book,
 * time-boxed HMAC signature instead.
 *
 * `playback/prepare` calls [signedQuery] to produce
 * `?u=<userId>&exp=<epochSec>&sig=<hex>`. The cover route calls [verify]. A
 * leaked URL grants ONE book's cover to ONE user for [ttl] — far tighter than
 * a full access token in the query string.
 *
 * The key-derivation label `"listenup-cover-url-v1"` is distinct from the
 * audio signer's label, ensuring an audio signature can never authorize a
 * cover request and vice versa.
 *
 * This class deliberately mirrors [AudioUrlSigner]; the constant-time
 * comparison and the hex-length guard must stay in sync between the two
 * (a shared base is a deferred follow-up).
 *
 * @param signingKey derived from the JWT secret so operators manage no new
 *   secret: `HMAC-SHA256(jwtSecret, "listenup-cover-url-v1")`.
 * @param ttl default 12h — consistent with [AudioUrlSigner].
 */
class CoverUrlSigner(
    signingKey: ByteArray,
    private val ttl: Duration = 12.hours,
    private val clock: Clock = Clock.System,
) {
    private val key: HMAC.Key =
        CryptographyProvider.Default
            .get(HMAC)
            .keyDecoder(SHA256)
            .decodeFromByteArrayBlocking(HMAC.Key.Format.RAW, signingKey)

    /**
     * Returns `u=<userId>&exp=<epochSec>&sig=<hex>` for the cover URL of
     * `(userId, bookId)`. The `bookId` is a path segment owned by the route
     * and is not repeated in the query string.
     */
    fun signedQuery(
        userId: String,
        bookId: String,
    ): String {
        val exp = (clock.now() + ttl).epochSeconds
        val sig = hmacHex(payload(userId, bookId, exp))
        return "u=${userId.encodeURLParameter()}&exp=$exp&sig=$sig"
    }

    /**
     * Returns true when [sig] is a valid, unexpired HMAC signature for the
     * given `(userId, bookId, exp)` tuple.
     *
     * Verification is constant-time: the expected signature is always computed
     * in full and compared byte-by-byte via XOR accumulation, so timing does
     * not reveal how many bytes matched.
     */
    fun verify(
        userId: String,
        bookId: String,
        exp: Long,
        sig: String,
    ): Boolean {
        if (exp <= clock.now().epochSeconds) return false
        val expectedBytes = hmacBytes(payload(userId, bookId, exp))
        val actualBytes = hexToBytes(sig) ?: return false
        return constantTimeEquals(expectedBytes, actualBytes)
    }

    private fun payload(
        userId: String,
        bookId: String,
        exp: Long,
    ) = "$userId|$bookId|$exp"

    private fun hmacHex(message: String): String = hmacBytes(message).toHexString()

    private fun hmacBytes(message: String): ByteArray =
        key.signatureGenerator().generateSignatureBlocking(message.encodeToByteArray())

    /**
     * Constant-time byte comparison via XOR accumulation. Returns true only
     * when both arrays have the same length and every byte pair is equal.
     * Does not short-circuit on the first mismatch, so timing does not leak
     * how many bytes matched.
     */
    private fun constantTimeEquals(
        a: ByteArray,
        b: ByteArray,
    ): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
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
         * `HMAC-SHA256(jwtSecret.encodeToByteArray(), "listenup-cover-url-v1")`.
         *
         * The distinct label ensures audio signatures can never authorize cover
         * requests and vice versa.
         *
         * Uses the blocking API — safe to call from Koin module construction
         * (non-suspend context).
         */
        fun deriveSigningKey(jwtSecret: String): ByteArray {
            val provider = CryptographyProvider.Default
            val tempKey =
                provider
                    .get(HMAC)
                    .keyDecoder(SHA256)
                    .decodeFromByteArrayBlocking(HMAC.Key.Format.RAW, jwtSecret.encodeToByteArray())
            return tempKey
                .signatureGenerator()
                .generateSignatureBlocking("listenup-cover-url-v1".encodeToByteArray())
        }
    }
}
