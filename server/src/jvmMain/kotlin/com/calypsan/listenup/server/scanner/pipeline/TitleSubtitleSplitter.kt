package com.calypsan.listenup.server.scanner.pipeline

/**
 * Derives a `title` / `subtitle` pair from a single title string.
 *
 * Two split strategies, tried in order:
 *  - D0 (descriptive tail): a trailing `: A …`/`: An …`/`: The …` segment is a
 *    clearly-descriptive subtitle. With a **greedy** title, this splits at the
 *    *last* such segment — so a multi-colon title like
 *    `"The Land: Alliances: A LitRPG Saga"` keeps `"The Land: Alliances"` whole
 *    and lifts off `"A LitRPG Saga"`. A single-colon descriptive title
 *    (`"Sapiens: A Brief History…"`) splits identically to the first-colon rule.
 *  - First-colon: otherwise, split on the first `": "` (colon + space). Colon is
 *    the standard title/subtitle publishing convention; a spaced dash is too
 *    low-precision to split on (it is a filename-field separator).
 *
 * Guardrails keep precision high (applied to both strategies):
 *  - G1: requires `": "` (colon + space), so times/ratios/namespaces
 *    ("1:30", "Ratio 3:1", "Vol:2") are never split.
 *  - G2: both sides must be non-empty after trimming.
 *  - G3: a subtitle that is a bare number or a volume token
 *    ("Dune: 2", "Foundation: Book 3") is a series volume, not a subtitle —
 *    left whole.
 *
 * Returns `(title.trim(), null)` when no split applies.
 */
object TitleSubtitleSplitter {
    private val colonSpace = Regex("""^(.+?): (.+)$""")

    // Greedy title so the split lands at the LAST ": A/An/The …" segment.
    private val descriptiveTail =
        Regex("""^(.+):\s+((?:A|An|The)\s+\S.*)$""", RegexOption.IGNORE_CASE)
    private val volumeSubtitle =
        Regex("""^(?:\d+|(?:book|vol\.?|volume|part|pt\.?|#)\s*\d+)$""", RegexOption.IGNORE_CASE)

    fun split(title: String): Pair<String, String?> {
        val trimmed = title.trim()
        descriptiveTail.matchEntire(trimmed)?.let { match ->
            val left = match.groupValues[1].trim()
            val right = match.groupValues[2].trim()
            if (left.isNotEmpty() && right.isNotEmpty() && !volumeSubtitle.matches(right)) {
                return left to right
            }
        }
        val match = colonSpace.matchEntire(trimmed) ?: return trimmed to null
        val left = match.groupValues[1].trim()
        val right = match.groupValues[2].trim()
        if (left.isEmpty() || right.isEmpty()) return trimmed to null
        if (volumeSubtitle.matches(right)) return trimmed to null
        return left to right
    }
}
