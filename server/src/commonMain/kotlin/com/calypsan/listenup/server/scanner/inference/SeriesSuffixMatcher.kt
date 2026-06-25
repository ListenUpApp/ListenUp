package com.calypsan.listenup.server.scanner.inference

/**
 * Conservative recognition / removal of a **trailing series suffix** in a book title, and detection
 * of a string that is really a series reference (a bogus "subtitle" tag that actually holds the series).
 *
 * High precision by design: only a strict, *trailing*, anchored `Book/Vol/Volume + number` suffix is ever
 * stripped; a series *name* in the title's prose (e.g. "Harry Potter and the Half-Blood Prince") is never
 * touched. No confident match -> the input is returned unchanged. `Part` is deliberately excluded from the
 * keyword set: a trailing "Part N" is far more often a real title's internal structure (Dostoevsky, memoirs)
 * than a series position, so stripping it would destroy legitimate text.
 */
internal object SeriesSuffixMatcher {
    // Cardinal number: a digit run, or an English word one..twenty. Kept as a word list so [NUM]
    // interpolates as a short raw string (a single inline literal would exceed the line-length limit).
    private val WORD_NUMBERS =
        listOf(
            "one",
            "two",
            "three",
            "four",
            "five",
            "six",
            "seven",
            "eight",
            "nine",
            "ten",
            "eleven",
            "twelve",
            "thirteen",
            "fourteen",
            "fifteen",
            "sixteen",
            "seventeen",
            "eighteen",
            "nineteen",
            "twenty",
        )
    private val NUM = """(?:\d+|${WORD_NUMBERS.joinToString("|")})"""

    // Series-position keyword: Book, Vol, Vol., Volume. `Part` is intentionally absent (see class KDoc).
    private const val LABEL = """(?:book|vol\.?|volume)"""

    // Form A — colon-prefixed series tail: ": <series>, Book N"  ("…: Chaos Seeds, Book 3" -> dropped).
    // Applied by [stripTrailingSeriesSuffix] ONLY to titles with 2+ colons. With a single colon, that
    // colon is the primary title:subtitle boundary, and "Title: Subtitle, Book N" far more often holds a
    // real subtitle ("Dune: Messiah, Book 2") than a colon-delimited series — stripping across it would
    // destroy the subtitle, which nothing downstream can recover. With 2+ colons an earlier colon already
    // carries the subtitle, so a trailing ": <words>, Book N" segment is confidently a series tail.
    private val colonSeriesSuffix =
        Regex("""\s*:\s*[^:]+,\s*$LABEL\s+$NUM\s*$""", RegexOption.IGNORE_CASE)

    // Forms B/C are always safe — end-anchored and they never cross a colon — so they apply unconditionally:
    //   Form B — bare comma:      ", Book N" / ", Volume N"   ("Wheel of Time, Volume 14" -> "Wheel of Time")
    //   Form C — parenthesised:   "(<...>, Book N)" / "(<...> #N)"   ("Catching Fire (Hunger Games, Book Two)")
    private val trailingSuffix =
        Regex(
            """\s*(?:,\s*$LABEL\s+$NUM|\([^)]*(?:$LABEL\s+$NUM|#\s*$NUM)\))\s*$""",
            RegexOption.IGNORE_CASE,
        )

    // A standalone string that IS a series reference (rather than a real title), optionally in parens:
    //   - bare "LABEL NUM" ("Book 3", "Volume 14")
    //   - "<series name>, LABEL NUM" ("Chaos Seeds, Book 3")
    private val seriesReference =
        Regex(
            """^\(?\s*(?:[^,()]+,\s*)?$LABEL\s+$NUM\s*\)?$""",
            RegexOption.IGNORE_CASE,
        )

    /**
     * Returns [title] with any trailing series suffix stripped, or [title] unchanged when no
     * high-confidence suffix is found.
     */
    fun stripTrailingSeriesSuffix(title: String): String {
        val deColoned =
            if (title.count { it == ':' } >= 2) colonSeriesSuffix.replaceFirst(title, "") else title
        val stripped = trailingSuffix.replaceFirst(deColoned, "").trim()
        return stripped.ifBlank { title.trim() }
    }

    /**
     * Returns `true` when [text] is itself a series reference — i.e. the whole string encodes a
     * series-and-position rather than a real subtitle (e.g. a mistagged SUBTITLE tag whose value is
     * "Chaos Seeds, Book 3").
     *
     * Precision/recall note: `"<phrase>, Book N"` and `"<phrase>, Volume N"` are treated as series
     * references, so a genuine subtitle of exactly that shape (e.g. "A Memoir, Volume 1") is a deliberate,
     * accepted false positive. It is rare in practice and the cost — discarding the subtitle — is far
     * cheaper than letting an obvious series tag masquerade as one. `Part` is excluded (see [LABEL]),
     * which removes the most common false-positive family ("…, Part Two").
     */
    fun isSeriesReference(text: String): Boolean = seriesReference.matches(text.trim())
}
