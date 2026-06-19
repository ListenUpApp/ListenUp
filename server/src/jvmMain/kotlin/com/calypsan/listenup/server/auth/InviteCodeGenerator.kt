package com.calypsan.listenup.server.auth

import java.security.SecureRandom
import java.util.Base64

/**
 * Produces high-entropy 16-byte (128-bit) invite codes encoded as base64url
 * (no padding). Each code is 22 chars. Single-use: a claimed invite is never
 * reissued. Mirrors [RefreshTokenGenerator]; the smaller byte count reflects
 * the shorter validity window of an invite versus a refresh token.
 */
class InviteCodeGenerator(
    private val random: SecureRandom = SecureRandom(),
) {
    fun generate(): String {
        val bytes = ByteArray(CODE_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        private const val CODE_BYTES = 16
    }
}
