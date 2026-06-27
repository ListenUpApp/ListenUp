package com.calypsan.listenup.server.sync

import java.text.Normalizer

private val COMBINING_MARKS = Regex("\\p{Mn}")

/**
 * JVM actual: full Unicode NFKD decomposition, then drop the combining marks — byte-identical to the
 * historical `TagSlug`/`MoodSlug` behaviour, so existing stored slugs are unaffected.
 */
internal actual fun foldDiacritics(raw: String): String =
    Normalizer.normalize(raw, Normalizer.Form.NFKD).replace(COMBINING_MARKS, "")
