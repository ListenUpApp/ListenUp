package com.calypsan.listenup.server.hardcover

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlin.io.encoding.Base64

/**
 * Encrypts Hardcover OAuth tokens at rest with AES-256-GCM (authenticated: tampering is detected,
 * not decrypted to garbage). The key is derived from the server's JWT secret — operators manage no
 * new secret, the same posture as [com.calypsan.listenup.server.audio.AudioUrlSigner] — so a leaked
 * database or backup archive alone does not expose anyone's Hardcover account, and a restore onto a
 * server with a DIFFERENT secret fails closed ([decrypt] → null) rather than silently.
 *
 * The stored form is Base64 of GCM's own output (nonce ‖ ciphertext ‖ tag); a fresh random nonce per
 * [encrypt] means the same token never encrypts to the same string twice.
 */
class HardcoverTokenCipher(
    key: ByteArray,
) {
    private val cipher =
        CryptographyProvider.Default
            .get(AES.GCM)
            .keyDecoder()
            .decodeFromByteArrayBlocking(AES.Key.Format.RAW, key)
            .cipher()

    /** Encrypts [plaintext] to a storable string. */
    fun encrypt(plaintext: String): String = Base64.encode(cipher.encryptBlocking(plaintext.encodeToByteArray()))

    /** Decrypts what [encrypt] produced, or null when it can't: a different key, tampering, or garbage. */
    fun decrypt(stored: String): String? {
        val sealed =
            try {
                Base64.decode(stored)
            } catch (_: IllegalArgumentException) {
                return null
            }
        // OpenSSL indexes out of bounds on a value too short to hold a tag; refuse it before either provider sees it.
        if (sealed.size < NONCE_BYTES + TAG_BYTES) return null
        return nullOnAuthenticationFailure { cipher.decryptBlocking(sealed) }?.decodeToString()
    }

    companion object {
        private const val NONCE_BYTES = 12
        private const val TAG_BYTES = 16

        /**
         * The AES-256 key for [jwtSecret]: `HMAC-SHA256(jwtSecret, "listenup-hardcover-tokens-v1")`.
         * The label keeps this key independent of every other key derived from the same secret.
         */
        fun deriveKey(jwtSecret: String): ByteArray =
            CryptographyProvider.Default
                .get(HMAC)
                .keyDecoder(SHA256)
                .decodeFromByteArrayBlocking(HMAC.Key.Format.RAW, jwtSecret.encodeToByteArray())
                .signatureGenerator()
                .generateSignatureBlocking("listenup-hardcover-tokens-v1".encodeToByteArray())
    }
}

/**
 * Runs an AES-GCM [decrypt] and returns null when the tag doesn't verify (wrong key or tampering).
 * Each cryptography-kotlin provider reports that failure with its own type — the JDK provider lets
 * `javax.crypto.AEADBadTagException` through, OpenSSL raises `IllegalStateException` — so each
 * platform names exactly its own, and nothing broader is swallowed.
 */
internal expect fun nullOnAuthenticationFailure(decrypt: () -> ByteArray): ByteArray?
