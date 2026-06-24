package com.calypsan.listenup.server.auth

import dev.whyoleg.cryptography.random.CryptographyRandom
import kotlin.io.encoding.Base64

/**
 * Produces high-entropy 16-byte (128-bit) invite codes encoded as base64url (no padding) — 22 chars.
 * Single-use. Smaller than a refresh token, reflecting the shorter validity window.
 */
class InviteCodeGenerator {
    fun generate(): String = URL_NO_PAD.encode(CryptographyRandom.nextBytes(CODE_BYTES))

    companion object {
        private const val CODE_BYTES = 16
        private val URL_NO_PAD = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    }
}
