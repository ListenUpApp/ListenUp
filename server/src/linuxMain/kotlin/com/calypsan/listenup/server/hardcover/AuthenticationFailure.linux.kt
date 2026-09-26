package com.calypsan.listenup.server.hardcover

/**
 * OpenSSL actual: a failed `EVP_CipherFinal` (the GCM tag check) surfaces as the provider's
 * `IllegalStateException("OPENSSL failure: …")`. Nothing inside [decrypt] suspends, so no
 * `CancellationException` can be in flight here.
 */
internal actual fun nullOnAuthenticationFailure(decrypt: () -> ByteArray): ByteArray? =
    try {
        decrypt()
    } catch (_: IllegalStateException) {
        null
    }
