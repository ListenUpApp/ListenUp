package com.calypsan.listenup.server.scanner.pipeline

/**
 * Derives a `title` / `subtitle` pair from a single title string by splitting on
 * the first `": "` (colon + space). Colon is the standard title/subtitle
 * publishing convention; a spaced dash in a title string is too low-precision to
 * split on (it is a filename-field separator), so only the colon is used here.
 *
 * Guardrails keep precision high:
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
    private val volumeSubtitle =
        Regex("""^(?:\d+|(?:book|vol\.?|volume|part|pt\.?|#)\s*\d+)$""", RegexOption.IGNORE_CASE)

    fun split(title: String): Pair<String, String?> {
        val trimmed = title.trim()
        val match = colonSpace.matchEntire(trimmed) ?: return trimmed to null
        val left = match.groupValues[1].trim()
        val right = match.groupValues[2].trim()
        if (left.isEmpty() || right.isEmpty()) return trimmed to null
        if (volumeSubtitle.matches(right)) return trimmed to null
        return left to right
    }
}
