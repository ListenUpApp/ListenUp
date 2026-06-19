package com.calypsan.listenup.server.auth

import java.security.SecureRandom
import java.util.Base64

/**
 * Produces opaque 32-byte refresh tokens encoded as base64url (no padding).
 * Each token is 43 chars. The server stores HMAC-SHA-256 of these (keyed with
 * a server-side pepper); the client holds the raw value and never decodes it.
 */
class RefreshTokenGenerator(
    private val random: SecureRandom = SecureRandom(),
) {
    fun generate(): String {
        val bytes = ByteArray(REFRESH_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        private const val REFRESH_BYTES = 32
    }
}
