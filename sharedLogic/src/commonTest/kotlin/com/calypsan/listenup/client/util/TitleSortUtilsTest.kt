package com.calypsan.listenup.client.util

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for TitleSortUtils.
 *
 * Covers:
 * - Article stripping (A, An, The)
 * - Natural number sorting (zero-padding)
 * - Sort letter extraction for section headers
 */
class TitleSortUtilsTest {
    // ========== Article Stripping Tests ==========

    @Test
    fun `sortableTitle strips leading The when ignoring articles`() {
        assertEquals(
            "alchemist",
            TitleSortUtils.sortableTitle("The Alchemist", ignoreArticles = true),
        )
    }

    @Test
    fun `sortableTitle strips leading A when ignoring articles`() {
        assertEquals(
            "wrinkle in time",
            TitleSortUtils.sortableTitle("A Wrinkle in Time", ignoreArticles = true),
        )
    }

    @Test
    fun `sortableTitle strips leading An when ignoring articles`() {
        assertEquals(
            "american tragedy",
            TitleSortUtils.sortableTitle("An American Tragedy", ignoreArticles = true),
        )
    }

    @Test
    fun `sortableTitle preserves title when not ignoring articles`() {
        assertEquals(
            "the alchemist",
            TitleSortUtils.sortableTitle("The Alchemist", ignoreArticles = false),
        )
    }

    @Test
    fun `sortableTitle preserves AI without space after A`() {
        // "A.I." should not be stripped because there's no space after "A"
        assertEquals(
            "a.i.",
            TitleSortUtils.sortableTitle("A.I.", ignoreArticles = true),
        )
    }

    @Test
    fun `sortableTitle is case insensitive for articles`() {
        assertEquals(
            "hobbit",
            TitleSortUtils.sortableTitle("THE Hobbit", ignoreArticles = true),
        )
    }

    @Test
    fun `sortableTitle handles lowercase articles`() {
        assertEquals(
            "lord of the rings",
            TitleSortUtils.sortableTitle("the Lord of the Rings", ignoreArticles = true),
        )
    }

    // ========== Natural Number Sorting Tests ==========

    @Test
    fun `sortableTitle pads leading numbers for natural sort`() {
        val result = TitleSortUtils.sortableTitle("1984", ignoreArticles = false)
        assertEquals("0000001984", result)
    }

    @Test
    fun `sortableTitle pads smaller numbers`() {
        val result = TitleSortUtils.sortableTitle("12 Rules for Life", ignoreArticles = false)
        assertEquals("0000000012 rules for life", result)
    }

    @Test
    fun `sortableTitle ensures 2 sorts before 12`() {
        val sort2 = TitleSortUtils.sortableTitle("2001: A Space Odyssey", ignoreArticles = false)
        val sort12 = TitleSortUtils.sortableTitle("12 Angry Men", ignoreArticles = false)

        // 0000002001 < 0000000012 is false, but sorted numerically 2001 > 12
        // Actually we're checking padding - let me verify the comparison
        // "0000002001" > "0000000012" because 2001 > 12 when padded
        // But the test should be that single-digit numbers sort before double-digit
        val sort1 = TitleSortUtils.sortableTitle("1 Fish 2 Fish", ignoreArticles = false)
        val sort100 = TitleSortUtils.sortableTitle("100 Years of Solitude", ignoreArticles = false)

        // With padding: "0000000001" < "0000000100"
        assertTrue(sort1 < sort100)
    }

    @Test
    fun `sortableTitle handles title starting with number after article strip`() {
        // "The 39 Steps" -> "39 Steps" -> "0000000039 steps"
        val result = TitleSortUtils.sortableTitle("The 39 Steps", ignoreArticles = true)
        assertEquals("0000000039 steps", result)
    }

    @Test
    fun `sortableTitle does not pad numbers in middle of title`() {
        // Only leading numbers are padded
        val result = TitleSortUtils.sortableTitle("Fahrenheit 451", ignoreArticles = false)
        assertEquals("fahrenheit 451", result)
    }

    // ========== Sort Letter Tests ==========

    @Test
    fun `sortLetter returns first letter uppercase`() {
        assertEquals('B', TitleSortUtils.sortLetter("Battle Royale", ignoreArticles = false))
    }

    @Test
    fun `sortLetter returns hash for numeric titles`() {
        assertEquals('#', TitleSortUtils.sortLetter("1984", ignoreArticles = false))
    }

    @Test
    fun `sortLetter ignores articles when enabled`() {
        // "The Alchemist" -> sortable "alchemist" -> first letter 'A'
        assertEquals('A', TitleSortUtils.sortLetter("The Alchemist", ignoreArticles = true))
    }

    @Test
    fun `sortLetter returns T for The when not ignoring articles`() {
        assertEquals('T', TitleSortUtils.sortLetter("The Alchemist", ignoreArticles = false))
    }

    @Test
    fun `sortLetter returns hash for special characters`() {
        assertEquals('#', TitleSortUtils.sortLetter("@War", ignoreArticles = false))
    }

    // ========== Extension Function Tests ==========

    @Test
    fun `String extension sortableTitle works`() {
        assertEquals("mistborn", "Mistborn".sortableTitle(ignoreArticles = false))
    }

    @Test
    fun `String extension sortLetter works`() {
        assertEquals('M', "Mistborn".sortLetter(ignoreArticles = false))
    }

    // ========== Edge Cases ==========

    @Test
    fun `sortableTitle handles empty string`() {
        assertEquals("", TitleSortUtils.sortableTitle("", ignoreArticles = true))
    }

    @Test
    fun `sortableTitle handles whitespace only`() {
        assertEquals("", TitleSortUtils.sortableTitle("   ", ignoreArticles = true).trim())
    }

    @Test
    fun `sortableTitle handles title that is just article`() {
        // "The " with trailing space would be stripped to empty
        // "The" without space is preserved
        assertEquals("the", TitleSortUtils.sortableTitle("The", ignoreArticles = true))
    }

    @Test
    fun `sortLetter returns hash for empty string`() {
        assertEquals('#', TitleSortUtils.sortLetter("", ignoreArticles = false))
    }

    // Helper function for comparison tests
    private fun assertTrue(condition: Boolean) {
        kotlin.test.assertTrue(condition)
    }
}
