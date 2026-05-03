package com.calypsan.listenup.server.auth

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Deterministic HMAC-SHA-256 of refresh tokens, keyed with a server-side pepper.
 *
 * Refresh tokens are 32 random bytes (256 bits entropy) from `RefreshTokenGenerator`.
 * At that entropy class Argon2id-style key stretching adds nothing — but a keyed
 * one-way hash gives us:
 *   - O(1) indexed lookup (deterministic output, UNIQUE INDEX usable)
 *   - Defense in depth: a DB-only leak doesn't let an attacker pre-compute
 *     candidate hashes without also obtaining the pepper.
 *
 * Pepper rotation invalidates every stored hash and forces all sessions to
 * re-authenticate; treat the pepper as long-lived and rotate only on suspected
 * compromise. In production, set `LISTENUP_REFRESH_PEPPER` to a 32+ byte secret;
 * the application.conf default is for tests only.
 */
class RefreshTokenHasher(
    pepper: ByteArray,
) {
    private val key: SecretKeySpec = SecretKeySpec(pepper.copyOf(), HMAC_ALGORITHM)

    init {
        require(pepper.size >= MIN_PEPPER_BYTES) {
            "pepper must be at least $MIN_PEPPER_BYTES bytes"
        }
    }

    /** Returns lowercase hex of the HMAC-SHA-256 digest (64 chars). */
    fun hash(token: String): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM).apply { init(key) }
        val digest = mac.doFinal(token.toByteArray(Charsets.UTF_8))
        return digest.toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

    companion object {
        private const val HMAC_ALGORITHM = "HmacSHA256"
        private const val MIN_PEPPER_BYTES = 32
    }
}
