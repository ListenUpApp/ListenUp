package com.calypsan.listenup.server.embeddedmeta

/**
 * Splits a single raw genre tag value (`TCON`, `©gen`, `gnre`) into individual genres.
 *
 * Audiobook taggers pack multiple genres into one field separated by `;`, `/`,
 * or `,` (matching Audiobookshelf's genre handling). Hyphens are preserved so
 * compound genres like "Sci-Fi" survive. Trims each part, drops blanks, and
 * dedupes while preserving first-seen order.
 */
object GenreSplitter {
    private val separator = Regex("""\s*[;/,]\s*""")

    fun split(raw: String): List<String> =
        raw
            .split(separator)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
}
