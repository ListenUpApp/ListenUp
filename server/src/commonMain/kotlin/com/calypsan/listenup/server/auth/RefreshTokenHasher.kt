package com.calypsan.listenup.server.auth

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256

/**
 * Deterministic HMAC-SHA-256 of refresh tokens, keyed with a server-side pepper.
 * Gives O(1) indexed lookup (deterministic output, UNIQUE INDEX usable) and defense in
 * depth: a DB-only leak doesn't let an attacker pre-compute hashes without the pepper.
 * Pepper rotation invalidates every stored hash; treat it as long-lived.
 */
class RefreshTokenHasher(
    pepper: ByteArray,
) {
    init {
        require(pepper.size >= MIN_PEPPER_BYTES) {
            "pepper must be at least $MIN_PEPPER_BYTES bytes"
        }
    }

    private val key: HMAC.Key =
        CryptographyProvider.Default
            .get(HMAC)
            .keyDecoder(SHA256)
            .decodeFromByteArrayBlocking(HMAC.Key.Format.RAW, pepper.copyOf())

    /** Returns lowercase hex of the HMAC-SHA-256 digest (64 chars). */
    @OptIn(ExperimentalStdlibApi::class)
    fun hash(token: String): String =
        key
            .signatureGenerator()
            .generateSignatureBlocking(token.encodeToByteArray())
            .toHexString()

    companion object {
        private const val MIN_PEPPER_BYTES = 32
    }
}
