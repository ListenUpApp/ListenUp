package com.calypsan.listenup.server.matching

private const val COMBINING_MARKS_START = '̀'
private const val COMBINING_MARKS_END = 'ͯ'

/**
 * Each lower-case Latin letter that carries a mark, or is a ligature, mapped to its plain spelling. Latin-1
 * Supplement first, then Latin Extended-A. Plain Kotlin on purpose: this module compiles for Kotlin/Native,
 * which has no Unicode normaliser.
 */
private val PLAIN_SPELLING: Map<Char, String> =
    buildMap {
        listOf(
            "àáâãäåāăą" to "a",
            "æ" to "ae",
            "çćĉċč" to "c",
            "ðďđ" to "d",
            "èéêëēĕėęě" to "e",
            "ĝğġģ" to "g",
            "ĥħ" to "h",
            "ìíîïĩīĭįı" to "i",
            "ĳ" to "ij",
            "ĵ" to "j",
            "ķĸ" to "k",
            "ĺļľŀł" to "l",
            "ñńņňŉŋ" to "n",
            "òóôõöøōŏő" to "o",
            "œ" to "oe",
            "ŕŗř" to "r",
            "śŝşšſ" to "s",
            "ß" to "ss",
            "ţťŧ" to "t",
            "þ" to "th",
            "ùúûüũūŭůűų" to "u",
            "ŵ" to "w",
            "ýÿŷ" to "y",
            "źżž" to "z",
        ).forEach { (letters, plain) -> letters.forEach { put(it, plain) } }
    }

/**
 * Lower-cases [this] and removes what makes two spellings of one name differ: combining marks, and
 * the precomposed Latin letters that carry them. Catalogues and file tags disagree on accents far
 * more often than they disagree on the person. Other scripts pass through unchanged.
 */
internal fun String.foldForMatching(): String =
    buildString {
        for (char in this@foldForMatching) {
            val lower = char.lowercaseChar()
            if (lower in COMBINING_MARKS_START..COMBINING_MARKS_END) continue
            val plain = PLAIN_SPELLING[lower]
            if (plain != null) append(plain) else append(lower)
        }
    }
