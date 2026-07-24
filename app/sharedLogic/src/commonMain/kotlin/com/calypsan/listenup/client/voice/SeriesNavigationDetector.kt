package com.calypsan.listenup.client.voice

/**
 * Result of series navigation detection.
 */
sealed class SeriesNavigation {
    /** Play the next book in the current series */
    data object Next : SeriesNavigation()

    /** Play the first book in the current series */
    data object First : SeriesNavigation()

    /** Play a specific book by sequence number (e.g., "book 2", "1.5") */
    data class BySequence(
        val sequence: String,
    ) : SeriesNavigation()

    /** Query is not a series navigation request */
    data object NotSeriesNavigation : SeriesNavigation()
}

/**
 * Detects whether a voice query is requesting series navigation
 * (next book, previous book, specific sequence number).
 */
object SeriesNavigationDetector {
    private val NEXT_PATTERNS =
        setOf(
            "next book",
            "next one",
            "the next one",
            "next in series",
        )

    private val FIRST_PATTERNS =
        setOf(
            "first book",
            "start of",
            "beginning",
        )

    // Matches: "book 2", "book 1.5"
    private val NUMERIC_SEQUENCE_REGEX =
        Regex(
            """book\s+(\d+(?:\.\d+)?)""",
            RegexOption.IGNORE_CASE,
        )

    // Matches: "second book", "third book", etc.
    private val WORD_SEQUENCE_REGEX =
        Regex(
            """(first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth)\s+book""",
            RegexOption.IGNORE_CASE,
        )

    private val WORD_TO_NUMBER =
        mapOf(
            "first" to "1",
            "second" to "2",
            "third" to "3",
            "fourth" to "4",
            "fifth" to "5",
            "sixth" to "6",
            "seventh" to "7",
            "eighth" to "8",
            "ninth" to "9",
            "tenth" to "10",
        )

    /**
     * Detects series navigation intent from a voice query.
     */
    fun detect(query: String): SeriesNavigation {
        val normalized = query.lowercase().trim()
        if (normalized.isEmpty()) return SeriesNavigation.NotSeriesNavigation

        // Check for "next" patterns
        if (NEXT_PATTERNS.any { normalized.contains(it) }) {
            return SeriesNavigation.Next
        }

        // Check for "first" patterns
        if (FIRST_PATTERNS.any { normalized.contains(it) }) {
            return SeriesNavigation.First
        }

        // Check for numeric sequence (e.g., "book 2")
        NUMERIC_SEQUENCE_REGEX.find(normalized)?.let { match ->
            return SeriesNavigation.BySequence(match.groupValues[1])
        }

        // Check for word sequence (e.g., "second book")
        WORD_SEQUENCE_REGEX.find(normalized)?.let { match ->
            val word = match.groupValues[1].lowercase()
            WORD_TO_NUMBER[word]?.let { number ->
                return SeriesNavigation.BySequence(number)
            }
        }

        return SeriesNavigation.NotSeriesNavigation
    }
}
