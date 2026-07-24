package com.calypsan.listenup.client.util

/**
 * Utilities for article-aware title sorting with natural number handling.
 *
 * Features:
 * - Leading articles (A, An, The) can be ignored when sorting
 * - Numeric titles sort before alphabetic titles (under "#" section)
 * - Natural sorting for numbers: 2 < 12 < 100 < 2001
 *
 * References:
 * - NISO Guidelines for Alphabetical Arrangement (section 4.6)
 * - Audiobookshelf "Ignore prefixes when sorting" feature
 * - iTunes/Apple Music Sort Name field
 */
object TitleSortUtils {
    /**
     * English articles to ignore when sorting.
     * Structured as a list for future i18n expansion (German: Der/Die/Das, etc.)
     */
    private val ENGLISH_ARTICLES = listOf("the", "a", "an")

    /**
     * Pattern matches leading articles followed by whitespace.
     * Uses word boundary to avoid matching "A.I." or "The" at end of title.
     */
    private val articlePattern =
        Regex(
            "^(${ENGLISH_ARTICLES.joinToString("|")})\\s+",
            RegexOption.IGNORE_CASE,
        )

    /**
     * Pattern matches leading digits for natural sorting.
     */
    private val leadingNumberPattern = Regex("^(\\d+)")

    /**
     * Padding width for natural number sorting.
     * Supports numbers up to 10 digits (9,999,999,999).
     */
    private const val NUMBER_PAD_WIDTH = 10

    /**
     * Returns a sortable version of the title.
     *
     * Article handling (when [ignoreArticles] is true):
     * - "The Alchemist" → "alchemist"
     * - "A Wrinkle in Time" → "wrinkle in time"
     * - "A.I." → "a.i." (preserved - no space after "A")
     *
     * Natural number sorting:
     * - "1984" → "0000001984" (sorts before "12 Rules")
     * - "12 Rules for Life" → "0000000012 rules for life"
     * - "2001: A Space Odyssey" → "0000002001: a space odyssey"
     *
     * This ensures numeric titles:
     * 1. Sort before alphabetic titles (digits < letters in ASCII)
     * 2. Sort naturally within the "#" section (2 < 12 < 2001)
     */
    fun sortableTitle(
        title: String,
        ignoreArticles: Boolean,
    ): String {
        // Step 1: Strip articles if enabled
        val withoutArticles =
            if (ignoreArticles) {
                title.replace(articlePattern, "")
            } else {
                title
            }

        // Step 2: Apply natural number sorting by zero-padding leading digits
        val leadingMatch = leadingNumberPattern.find(withoutArticles)
        val naturalSorted =
            if (leadingMatch != null) {
                val number = leadingMatch.value
                val padded = number.padStart(NUMBER_PAD_WIDTH, '0')
                withoutArticles.replaceFirst(number, padded)
            } else {
                withoutArticles
            }

        return naturalSorted.lowercase()
    }

    /**
     * Returns the first character for section header grouping.
     *
     * @return Uppercase letter for alphabetic titles, '#' for numeric/special
     */
    fun sortLetter(
        title: String,
        ignoreArticles: Boolean,
    ): Char {
        val sortable = sortableTitle(title, ignoreArticles)
        val first = sortable.firstOrNull()?.uppercaseChar() ?: '#'
        return if (first.isLetter()) first else '#'
    }
}

/**
 * Extension function for convenient access.
 */
fun String.sortableTitle(ignoreArticles: Boolean): String = TitleSortUtils.sortableTitle(this, ignoreArticles)

/**
 * Extension function for section header letter.
 */
fun String.sortLetter(ignoreArticles: Boolean): Char = TitleSortUtils.sortLetter(this, ignoreArticles)
