package com.calypsan.listenup.server.auth

/**
 * Native actual: compose a base letter followed by a single combining diacritic into its precomposed
 * form, over the lowercase Latin range — the realistic email domain ([Email.normalize] lowercases
 * before composing). Plain ASCII and already-composed text pass straight through, matching the JVM
 * `Normalizer` NFC the parity test pins both targets to.
 *
 * Only base+mark pairs that NFC canonically composes are listed (verified by `NfcNormalizeParityTest`
 * against `java.text.Normalizer`). Letters NFC leaves whole, multi-mark sequences, and non-Latin
 * scripts are passed through unchanged — they don't occur in email local-parts, and the JVM
 * `Normalizer` agrees on the covered domain.
 */
internal actual fun normalizeNfc(raw: String): String {
    var hasNonAscii = false
    for (ch in raw) {
        if (ch.code >= 0x80) {
            hasNonAscii = true
            break
        }
    }
    if (!hasNonAscii) return raw

    val out = StringBuilder(raw.length)
    var i = 0
    while (i < raw.length) {
        val base = raw[i]
        val composed = if (i + 1 < raw.length) COMPOSE[base]?.get(raw[i + 1]) else null
        if (composed != null) {
            out.append(composed)
            i += 2
        } else {
            out.append(base)
            i++
        }
    }
    return out.toString()
}

private const val GRAVE = '̀'
private const val ACUTE = '́'
private const val CIRCUMFLEX = '̂'
private const val TILDE = '̃'
private const val MACRON = '̄'
private const val BREVE = '̆'
private const val DOT_ABOVE = '̇'
private const val DIAERESIS = '̈'
private const val RING_ABOVE = '̊'
private const val DOUBLE_ACUTE = '̋'
private const val CARON = '̌'
private const val CEDILLA = '̧'
private const val OGONEK = '̨'

/** base letter -> (combining mark -> precomposed letter). Lowercase only; see [normalizeNfc]. */
private val COMPOSE: Map<Char, Map<Char, Char>> =
    mapOf(
        'a' to
            mapOf(
                GRAVE to 'à',
                ACUTE to 'á',
                CIRCUMFLEX to 'â',
                TILDE to 'ã',
                DIAERESIS to 'ä',
                RING_ABOVE to 'å',
                MACRON to 'ā',
                BREVE to 'ă',
                OGONEK to 'ą',
            ),
        'c' to mapOf(CEDILLA to 'ç', ACUTE to 'ć', CIRCUMFLEX to 'ĉ', DOT_ABOVE to 'ċ', CARON to 'č'),
        'd' to mapOf(CARON to 'ď'),
        'e' to
            mapOf(
                GRAVE to 'è',
                ACUTE to 'é',
                CIRCUMFLEX to 'ê',
                DIAERESIS to 'ë',
                MACRON to 'ē',
                BREVE to 'ĕ',
                DOT_ABOVE to 'ė',
                OGONEK to 'ę',
                CARON to 'ě',
            ),
        'g' to mapOf(CIRCUMFLEX to 'ĝ', BREVE to 'ğ', DOT_ABOVE to 'ġ', CEDILLA to 'ģ'),
        'h' to mapOf(CIRCUMFLEX to 'ĥ'),
        'i' to
            mapOf(
                GRAVE to 'ì',
                ACUTE to 'í',
                CIRCUMFLEX to 'î',
                DIAERESIS to 'ï',
                TILDE to 'ĩ',
                MACRON to 'ī',
                BREVE to 'ĭ',
                OGONEK to 'į',
            ),
        'j' to mapOf(CIRCUMFLEX to 'ĵ'),
        'k' to mapOf(CEDILLA to 'ķ'),
        'l' to mapOf(ACUTE to 'ĺ', CEDILLA to 'ļ', CARON to 'ľ'),
        'n' to mapOf(TILDE to 'ñ', ACUTE to 'ń', CEDILLA to 'ņ', CARON to 'ň'),
        'o' to
            mapOf(
                GRAVE to 'ò',
                ACUTE to 'ó',
                CIRCUMFLEX to 'ô',
                TILDE to 'õ',
                DIAERESIS to 'ö',
                MACRON to 'ō',
                BREVE to 'ŏ',
                DOUBLE_ACUTE to 'ő',
            ),
        'r' to mapOf(ACUTE to 'ŕ', CEDILLA to 'ŗ', CARON to 'ř'),
        's' to mapOf(ACUTE to 'ś', CIRCUMFLEX to 'ŝ', CEDILLA to 'ş', CARON to 'š'),
        't' to mapOf(CEDILLA to 'ţ', CARON to 'ť'),
        'u' to
            mapOf(
                GRAVE to 'ù',
                ACUTE to 'ú',
                CIRCUMFLEX to 'û',
                DIAERESIS to 'ü',
                TILDE to 'ũ',
                MACRON to 'ū',
                BREVE to 'ŭ',
                RING_ABOVE to 'ů',
                DOUBLE_ACUTE to 'ű',
                OGONEK to 'ų',
            ),
        'w' to mapOf(CIRCUMFLEX to 'ŵ'),
        'y' to mapOf(ACUTE to 'ý', DIAERESIS to 'ÿ', CIRCUMFLEX to 'ŷ'),
        'z' to mapOf(ACUTE to 'ź', DOT_ABOVE to 'ż', CARON to 'ž'),
    )
