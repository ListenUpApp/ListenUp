package com.calypsan.listenup.server.scanner.inference

/**
 * Result of parsing a single title-folder name. Every annotation that ABS
 * can encode in a title folder ends up here; the consumer (Analyzer)
 * decides which fields to keep based on context (e.g. `sequence` is only
 * meaningful when a series folder is present).
 */
internal data class ParsedTitle(
    val title: String,
    val subtitle: String? = null,
    val asin: String? = null,
    val narrators: List<String> = emptyList(),
    val publishedYear: Int? = null,
    val sequence: String? = null,
)

/**
 * Title-folder name parser, ported byte-for-byte from
 * `audiobookshelf/server/utils/scandir.js` (`getBookDataFromDir`,
 * `getASIN`, `getNarrator`, `getSequence`, `getPublishedYear`,
 * `getSubtitle`).
 *
 * The order of operations matters: ASIN → narrators → sequence (when a
 * series folder is present) → year → subtitle. Each stage strips the
 * matched portion from the working folder string before the next stage
 * runs, so a folder like `(2010) - Book 2 - The Way of Kings [B0015T963C] {Kramer}`
 * decomposes cleanly without regex collisions.
 *
 * **Compatibility note on narrator splitting.** ABS uses `parseNameString`
 * which understands `&`, ` and `, `;`, and `Last, First` formats. This parser
 * splits on the simple separators (`,`, `;`, `&`, ` and `) and trims;
 * full ABS parity for `Last, First` recombination is not yet implemented,
 * pending a real name-normalization pass. For the typical
 * `{Michael Kramer; Kate Reading}` form, the simple split is correct.
 */
internal object AbsTitleParser {
    private val asinPattern = Regex("""(?: |^)\[([A-Z0-9]{10})](?= |$)""")
    private val narratorPattern = Regex("""^(?<title>.*) \{(?<narrators>.*)}$""")
    private val sequencePattern =
        Regex(
            """^(?<volumeLabel>vol\.? |volume |book )?(?<sequence>\d{0,3}(?:\.\d{1,2})?)(?<trailingDot>\.?)(?: (?<suffix>.*))?$""",
            RegexOption.IGNORE_CASE,
        )
    private val yearPattern = Regex("""^ *\(?([0-9]{4})\)? * - *(.+)""")
    private val nameSeparators = Regex("""\s*(?:,|;|&| and )\s*""")

    fun parse(
        folder: String,
        hasSeriesFolder: Boolean = false,
        parseSubtitle: Boolean = false,
    ): ParsedTitle {
        var working = folder.trim()

        val (afterAsin, asin) = extractAsin(working)
        working = afterAsin

        val (afterNarrator, narrators) = extractNarrators(working)
        working = afterNarrator

        val sequence =
            if (hasSeriesFolder) {
                val (afterSequence, seq) = extractSequence(working)
                working = afterSequence
                seq
            } else {
                null
            }

        val (afterYear, year) = extractYear(working)
        working = afterYear

        val (title, subtitle) =
            if (parseSubtitle) {
                extractSubtitle(working)
            } else {
                working to null
            }

        return ParsedTitle(
            title = title.trim(),
            subtitle = subtitle?.trim()?.takeUnless { it.isEmpty() },
            asin = asin,
            narrators = narrators,
            publishedYear = year,
            sequence = sequence,
        )
    }

    private fun extractAsin(folder: String): Pair<String, String?> {
        val match = asinPattern.find(folder) ?: return folder to null
        val cleaned = folder.replace(match.value, "").replace("  ", " ").trim()
        return cleaned to match.groupValues[1]
    }

    private fun extractNarrators(folder: String): Pair<String, List<String>> {
        val match = narratorPattern.matchEntire(folder) ?: return folder to emptyList()
        val title = match.groups["title"]!!.value
        val raw = match.groups["narrators"]!!.value
        val names = raw.split(nameSeparators).map { it.trim() }.filter { it.isNotEmpty() }
        return title to names
    }

    private fun extractSequence(folder: String): Pair<String, String?> {
        val parts = folder.split(" - ").toMutableList()
        for (i in parts.indices) {
            val match = sequencePattern.matchEntire(parts[i]) ?: continue
            val volumeLabel = match.groups["volumeLabel"]?.value
            val sequence = match.groups["sequence"]?.value.orEmpty()
            val trailingDot = match.groups["trailingDot"]?.value.orEmpty()
            val suffix = match.groups["suffix"]?.value
            // ABS exclusion: a part with a suffix but no volume label and no
            // trailing dot is not a sequence (e.g. "101 Dalmations").
            if (suffix != null && volumeLabel == null && trailingDot.isEmpty()) continue
            // Empty sequence (regex allows `\d{0,3}`) — bail on this part too.
            if (sequence.isEmpty()) continue
            // Normalize: "06" → "6", "1.5" stays "1.5", "0.5" stays "0.5".
            val normalized =
                sequence.toDoubleOrNull()?.let { d ->
                    if (d == d.toLong().toDouble()) d.toLong().toString() else sequence
                } ?: sequence

            parts[i] = suffix.orEmpty()
            if (parts[i].isEmpty()) parts.removeAt(i)
            return parts.joinToString(" - ") to normalized
        }
        return folder to null
    }

    private fun extractYear(folder: String): Pair<String, Int?> {
        val match = yearPattern.matchEntire(folder) ?: return folder to null
        return match.groupValues[2] to match.groupValues[1].toIntOrNull()
    }

    private fun extractSubtitle(folder: String): Pair<String, String?> {
        val parts = folder.split(" - ")
        if (parts.size < 2) return folder to null
        return parts.first() to parts.drop(1).joinToString(" - ")
    }
}
