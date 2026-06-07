package com.calypsan.listenup.server.embeddedmeta

import com.calypsan.listenup.domain.embeddedmeta.SeriesEntry

/**
 * Parses series out of embedded-tag strings. Two shapes:
 *  - [zipSeries]: a dedicated `series` tag aligned with a `series-part` tag. When both carry
 *    `;`-separated lists of equal length, they zip into multiple memberships (a book in two
 *    series); otherwise the whole value is treated as one series. Mirrors Audiobookshelf's
 *    semicolon-array behaviour.
 *  - [parsePacked]: a single string that already packs `Name #seq` entries separated by `;`
 *    (the GROUPING-tag convention).
 */
object SeriesTagParser {
    // Matches " #1a" at the end of a chunk — sequence must not contain whitespace or `#`.
    private val sequenceSuffix = Regex("""\s*#([^#\s]+)\s*$""")

    /**
     * Zips a `series` tag value and an optional `series-part` tag value into a list of
     * [SeriesEntry] instances.
     *
     * When [name] contains semicolons and [part] contains the same number of semicolon-separated
     * segments, each pair becomes its own [SeriesEntry]. Otherwise the whole [name] is treated as
     * a single series name.
     */
    fun zipSeries(
        name: String?,
        part: String?,
    ): List<SeriesEntry> {
        val trimmedName = name?.trim().orEmpty()
        if (trimmedName.isEmpty()) return emptyList()

        if (trimmedName.contains(';') && part?.contains(';') == true) {
            val names = trimmedName.split(';').map { it.trim() }.filter { it.isNotEmpty() }
            val parts = part.split(';').map { it.trim() }.filter { it.isNotEmpty() }
            if (names.size > 1 && names.size == parts.size) {
                return names.mapIndexed { i, n -> SeriesEntry(name = n, sequence = parts[i]) }
            }
        }
        return listOf(SeriesEntry(name = trimmedName, sequence = part?.trim()?.ifEmpty { null }))
    }

    /**
     * Parses a packed series string of the form `"Name #seq; Name2 #seq2"` into a list of
     * [SeriesEntry] instances. The `#seq` suffix is optional; entries without it produce a
     * [SeriesEntry] with a `null` sequence.
     */
    fun parsePacked(raw: String): List<SeriesEntry> =
        raw
            .split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { chunk ->
                val match = sequenceSuffix.find(chunk)
                if (match != null) {
                    SeriesEntry(
                        name = chunk.removeRange(match.range).trim(),
                        sequence = match.groupValues[1],
                    )
                } else {
                    SeriesEntry(name = chunk, sequence = null)
                }
            }
}
