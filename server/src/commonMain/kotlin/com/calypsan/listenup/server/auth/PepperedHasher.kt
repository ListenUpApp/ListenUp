package com.calypsan.listenup.server.auth

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256

/**
 * Deterministic HMAC-SHA-256 of a high-entropy secret, keyed with a server-side pepper.
 *
 * Deterministic output means an indexed lookup rather than a scan-and-verify, which is why
 * this and not Argon2id: Argon2id exists to make *low*-entropy human passwords expensive to
 * grind, and buys nothing against a random secret behind an attempt budget. The pepper lives
 * outside the database, so a DB-only leak does not let an attacker pre-compute hashes.
 *
 * Rotating the pepper invalidates every stored hash; treat it as long-lived.
 */
class PepperedHasher(
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
    fun hash(secret: String): String =
        key
            .signatureGenerator()
            .generateSignatureBlocking(secret.encodeToByteArray())
            .toHexString()

    companion object {
        private const val MIN_PEPPER_BYTES = 32
    }
}
