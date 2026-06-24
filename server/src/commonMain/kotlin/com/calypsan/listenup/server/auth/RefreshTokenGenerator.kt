package com.calypsan.listenup.server.auth

import dev.whyoleg.cryptography.random.CryptographyRandom
import kotlin.io.encoding.Base64

/**
 * Produces opaque 32-byte refresh tokens encoded as base64url (no padding) — 43 chars.
 * The server stores the HMAC-SHA-256 of these; the client holds the raw value.
 */
class RefreshTokenGenerator {
    fun generate(): String = URL_NO_PAD.encode(CryptographyRandom.nextBytes(REFRESH_BYTES))

    companion object {
        private const val REFRESH_BYTES = 32
        private val URL_NO_PAD = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    }
}
