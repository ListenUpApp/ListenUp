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

    // Matches a cardinal number written as a digit or as English words one..twenty.
    private const val NUM =
        "(?:\\d+|one|two|three|four|five|six|seven|eight|nine|ten" +
            "|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty)"

    // Matches the series-position keyword: Book, Vol, Vol., Volume, Part.
    private const val LABEL = "(?:book|vol\\.?|volume|part)"

    // Trailing suffix forms recognised:
    //
    //   Form A — colon-prefixed: ": <series name>, Book N" at end of title
    //            (handles "Title: Series, Book N" → "Title")
    //   Form B — bare comma: ", Book N" or ", Volume N"
    //            (handles "Title, Book 6" → "Title")
    //   Form C — parenthesised: "(<anything>, Book N)" or "(<anything> #N)"
    //            (handles "Title (Series, Book One)" and "Title (Series #4)" → "Title")
    //
    // Forms are tried longest-match first by alternation ordering so that Form A
    // (which consumes more characters) wins over Form B when both could match.
    private val trailingSuffix =
        Regex(
            // Form A: strip ":<ws>series-name, LABEL NUM" from the end
            "\\s*(?::\\s*[^:]+,\\s*$LABEL\\s+$NUM" +
                // Form B: strip ", LABEL NUM" from the end
                "|,\\s*$LABEL\\s+$NUM" +
                // Form C: strip "(<...>, LABEL NUM)" or "(<...> #NUM)" from the end
                "|\\([^)]*(?:$LABEL\\s+$NUM|#\\s*$NUM)\\))\\s*\$",
            RegexOption.IGNORE_CASE,
        )

    // A string that IS a series reference (rather than a real title).
    // Matches, optionally wrapped in parens:
    //   - bare "LABEL NUM" (e.g. "Book 3", "Volume 14")
    //   - "<series name>, LABEL NUM" (e.g. "Chaos Seeds, Book 3")
    private val seriesReference =
        Regex(
            "^\\(?\\s*(?:[^,()]+,\\s*)?$LABEL\\s+$NUM\\s*\\)?\$",
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
