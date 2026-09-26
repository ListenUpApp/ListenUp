package com.calypsan.listenup.server.hardcover

import javax.crypto.AEADBadTagException

/** JDK actual: `Cipher.doFinal`'s checked [AEADBadTagException] reaches us unwrapped. */
internal actual fun nullOnAuthenticationFailure(decrypt: () -> ByteArray): ByteArray? =
    try {
        decrypt()
    } catch (_: AEADBadTagException) {
        null
    }
