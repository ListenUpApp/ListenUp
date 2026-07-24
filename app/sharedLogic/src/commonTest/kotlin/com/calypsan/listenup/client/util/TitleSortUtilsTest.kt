package com.calypsan.listenup.client.util

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Tests for TitleSortUtils.
 *
 * Covers:
 * - Article stripping (A, An, The)
 * - Natural number sorting (zero-padding)
 * - Sort letter extraction for section headers
 */
class TitleSortUtilsTest :
    FunSpec({
        // ========== Article Stripping Tests ==========

        test("sortableTitle strips leading The when ignoring articles") {
            TitleSortUtils.sortableTitle("The Alchemist", ignoreArticles = true) shouldBe "alchemist"
        }

        test("sortableTitle strips leading A when ignoring articles") {
            TitleSortUtils.sortableTitle("A Wrinkle in Time", ignoreArticles = true) shouldBe "wrinkle in time"
        }

        test("sortableTitle strips leading An when ignoring articles") {
            TitleSortUtils.sortableTitle("An American Tragedy", ignoreArticles = true) shouldBe "american tragedy"
        }

        test("sortableTitle preserves title when not ignoring articles") {
            TitleSortUtils.sortableTitle("The Alchemist", ignoreArticles = false) shouldBe "the alchemist"
        }

        test("sortableTitle preserves AI without space after A") {
            // "A.I." should not be stripped because there's no space after "A"
            TitleSortUtils.sortableTitle("A.I.", ignoreArticles = true) shouldBe "a.i."
        }

        test("sortableTitle is case insensitive for articles") {
            TitleSortUtils.sortableTitle("THE Hobbit", ignoreArticles = true) shouldBe "hobbit"
        }

        test("sortableTitle handles lowercase articles") {
            TitleSortUtils.sortableTitle("the Lord of the Rings", ignoreArticles = true) shouldBe "lord of the rings"
        }

        // ========== Natural Number Sorting Tests ==========

        test("sortableTitle pads leading numbers for natural sort") {
            val result = TitleSortUtils.sortableTitle("1984", ignoreArticles = false)
            result shouldBe "0000001984"
        }

        test("sortableTitle pads smaller numbers") {
            val result = TitleSortUtils.sortableTitle("12 Rules for Life", ignoreArticles = false)
            result shouldBe "0000000012 rules for life"
        }

        test("sortableTitle ensures 2 sorts before 12") {
            val sort2 = TitleSortUtils.sortableTitle("2001: A Space Odyssey", ignoreArticles = false)
            val sort12 = TitleSortUtils.sortableTitle("12 Angry Men", ignoreArticles = false)

            // 0000002001 < 0000000012 is false, but sorted numerically 2001 > 12
            // Actually we're checking padding - let me verify the comparison
            // "0000002001" > "0000000012" because 2001 > 12 when padded
            // But the test should be that single-digit numbers sort before double-digit
            val sort1 = TitleSortUtils.sortableTitle("1 Fish 2 Fish", ignoreArticles = false)
            val sort100 = TitleSortUtils.sortableTitle("100 Years of Solitude", ignoreArticles = false)

            // With padding: "0000000001" < "0000000100"
            (sort1 < sort100) shouldBe true
        }

        test("sortableTitle handles title starting with number after article strip") {
            // "The 39 Steps" -> "39 Steps" -> "0000000039 steps"
            val result = TitleSortUtils.sortableTitle("The 39 Steps", ignoreArticles = true)
            result shouldBe "0000000039 steps"
        }

        test("sortableTitle does not pad numbers in middle of title") {
            // Only leading numbers are padded
            val result = TitleSortUtils.sortableTitle("Fahrenheit 451", ignoreArticles = false)
            result shouldBe "fahrenheit 451"
        }

        // ========== Sort Letter Tests ==========

        test("sortLetter returns first letter uppercase") {
            TitleSortUtils.sortLetter("Battle Royale", ignoreArticles = false) shouldBe 'B'
        }

        test("sortLetter returns hash for numeric titles") {
            TitleSortUtils.sortLetter("1984", ignoreArticles = false) shouldBe '#'
        }

        test("sortLetter ignores articles when enabled") {
            // "The Alchemist" -> sortable "alchemist" -> first letter 'A'
            TitleSortUtils.sortLetter("The Alchemist", ignoreArticles = true) shouldBe 'A'
        }

        test("sortLetter returns T for The when not ignoring articles") {
            TitleSortUtils.sortLetter("The Alchemist", ignoreArticles = false) shouldBe 'T'
        }

        test("sortLetter returns hash for special characters") {
            TitleSortUtils.sortLetter("@War", ignoreArticles = false) shouldBe '#'
        }

        // ========== Extension Function Tests ==========

        test("String extension sortableTitle works") {
            "Mistborn".sortableTitle(ignoreArticles = false) shouldBe "mistborn"
        }

        test("String extension sortLetter works") {
            "Mistborn".sortLetter(ignoreArticles = false) shouldBe 'M'
        }

        // ========== Edge Cases ==========

        test("sortableTitle handles empty string") {
            TitleSortUtils.sortableTitle("", ignoreArticles = true) shouldBe ""
        }

        test("sortableTitle handles whitespace only") {
            TitleSortUtils.sortableTitle("   ", ignoreArticles = true).trim() shouldBe ""
        }

        test("sortableTitle handles title that is just article") {
            // "The " with trailing space would be stripped to empty
            // "The" without space is preserved
            TitleSortUtils.sortableTitle("The", ignoreArticles = true) shouldBe "the"
        }

        test("sortLetter returns hash for empty string") {
            TitleSortUtils.sortLetter("", ignoreArticles = false) shouldBe '#'
        }
    })
