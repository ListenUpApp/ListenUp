package com.calypsan.listenup.server.auth

import java.text.Normalizer

/** JVM actual: full Unicode NFC — byte-identical to the historical [Email.normalize] behaviour. */
internal actual fun normalizeNfc(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFC)
