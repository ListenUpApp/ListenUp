package com.calypsan.listenup.server.scanner.inference

/**
 * Conservative recognition / removal of a **trailing series suffix** in a book title, and detection
 * of a string that is really a series reference (a bogus "subtitle" tag that actually holds the series).
 *
 * High precision by design: only a strict, *trailing*, anchored `Book/Vol/Part + number` suffix is ever
 * stripped; a series *name* in the title's prose (e.g. "Harry Potter and the Half-Blood Prince") is never
 * touched. No confident match -> the input is returned unchanged.
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

    // Series-position keyword: Book, Vol, Vol., Volume, Part.
    private const val LABEL = """(?:book|vol\.?|volume|part)"""

    // Trailing suffix forms, tried longest-match first by alternation order so the colon form
    // (which consumes more) wins over the bare-comma form when both could match:
    //   Form A — colon-prefixed:  ": <series>, Book N"   ("Title: Series, Book N" -> "Title")
    //   Form B — bare comma:      ", Book N" / ", Volume N"
    //   Form C — parenthesised:   "(<...>, Book N)" / "(<...> #N)"
    private val trailingSuffix =
        Regex(
            """\s*(?::\s*[^:]+,\s*$LABEL\s+$NUM|,\s*$LABEL\s+$NUM|\([^)]*(?:$LABEL\s+$NUM|#\s*$NUM)\))\s*$""",
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
        val stripped = trailingSuffix.replaceFirst(title, "").trim()
        return stripped.ifBlank { title.trim() }
    }

    /**
     * Returns `true` when [text] is itself a series reference — i.e. the whole string encodes a
     * series-and-position rather than a real subtitle (e.g. a mistagged SUBTITLE tag whose value is
     * "Chaos Seeds, Book 3").
     */
    fun isSeriesReference(text: String): Boolean = seriesReference.matches(text.trim())
}
