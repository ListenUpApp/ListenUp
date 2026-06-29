package com.calypsan.listenup.server.sync

/**
 * Native actual: fold precomposed Latin letters to their base ASCII letter. Covers the Latin-1
 * Supplement and common Latin Extended-A ranges — the realistic input for tag / mood display names.
 *
 * Only letters that NFKD decomposes to *base + combining mark* are listed; letters NFKD leaves whole
 * (the ligatures `æ`/`œ`, the strokes `ø`/`ł`/`đ`, `ß`, `ð`, `þ`, dotless `ı`, …) are intentionally
 * absent — the JVM `Normalizer` also leaves them, and the shared `[^a-z0-9]+` slug step then collapses
 * them to `-` on both targets, so the two agree without enumerating them here. Exotic compatibility
 * decompositions NFKD performs (full-width forms, fractions) aren't folded; they don't occur in names.
 */
internal actual fun foldDiacritics(raw: String): String =
    buildString(raw.length) {
        for (ch in raw) append(LATIN_DIACRITIC_FOLD[ch] ?: ch)
    }

private val LATIN_DIACRITIC_FOLD: Map<Char, Char> =
    buildMap {
        fun fold(
            base: Char,
            accented: String,
        ) = accented.forEach { put(it, base) }

        fold('A', "ÀÁÂÃÄÅĀĂĄ")
        fold('a', "àáâãäåāăą")
        fold('C', "ÇĆĈĊČ")
        fold('c', "çćĉċč")
        fold('D', "Ď")
        fold('d', "ď")
        fold('E', "ÈÉÊËĒĔĖĘĚ")
        fold('e', "èéêëēĕėęě")
        fold('G', "ĜĞĠĢ")
        fold('g', "ĝğġģ")
        fold('H', "Ĥ")
        fold('h', "ĥ")
        fold('I', "ÌÍÎÏĨĪĬĮİ")
        fold('i', "ìíîïĩīĭį")
        fold('J', "Ĵ")
        fold('j', "ĵ")
        fold('K', "Ķ")
        fold('k', "ķ")
        fold('L', "ĹĻĽ")
        fold('l', "ĺļľ")
        fold('N', "ÑŃŅŇ")
        fold('n', "ñńņň")
        fold('O', "ÒÓÔÕÖŌŎŐ")
        fold('o', "òóôõöōŏő")
        fold('R', "ŔŖŘ")
        fold('r', "ŕŗř")
        fold('S', "ŚŜŞŠ")
        fold('s', "śŝşš")
        fold('T', "ŢŤ")
        fold('t', "ţť")
        fold('U', "ÙÚÛÜŨŪŬŮŰŲ")
        fold('u', "ùúûüũūŭůűų")
        fold('W', "Ŵ")
        fold('w', "ŵ")
        fold('Y', "ÝŶŸ")
        fold('y', "ýÿŷ")
        fold('Z', "ŹŻŽ")
        fold('z', "źżž")
    }
