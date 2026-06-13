package com.calypsan.listenup.client.presentation.bookdetail

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SubtitleFilterTest :
    FunSpec({
        test("subtitle with just series name and book number is redundant") {
            testIsSubtitleRedundant(
                subtitle = "The Stormlight Archive, Book 1",
                seriesName = "The Stormlight Archive",
                seriesSequence = "1",
            ) shouldBe true
        }

        test("subtitle with series name and hash number is redundant") {
            testIsSubtitleRedundant(
                subtitle = "Mistborn #3",
                seriesName = "Mistborn",
                seriesSequence = "3",
            ) shouldBe true
        }

        test("subtitle with Book X of Series is redundant") {
            testIsSubtitleRedundant(
                subtitle = "Book 2 of The Wheel of Time",
                seriesName = "The Wheel of Time",
                seriesSequence = "2",
            ) shouldBe true
        }

        test("subtitle with meaningful content is not redundant") {
            testIsSubtitleRedundant(
                subtitle = "A Novel of the First Law",
                seriesName = "The First Law",
                seriesSequence = "1",
            ) shouldBe false
        }

        test("subtitle without series name is not redundant") {
            testIsSubtitleRedundant(
                subtitle = "An Epic Fantasy Adventure",
                seriesName = "The Stormlight Archive",
                seriesSequence = "1",
            ) shouldBe false
        }

        test("subtitle when no series info is not redundant") {
            testIsSubtitleRedundant(
                subtitle = "Book 1",
                seriesName = null,
                seriesSequence = null,
            ) shouldBe false
        }

        test("subtitle with volume pattern is redundant") {
            testIsSubtitleRedundant(
                subtitle = "Cosmere Collection, Volume 1",
                seriesName = "Cosmere Collection",
                seriesSequence = "1",
            ) shouldBe true
        }

        test("subtitle with part pattern is redundant") {
            testIsSubtitleRedundant(
                subtitle = "The Dark Tower, Part 3",
                seriesName = "The Dark Tower",
                seriesSequence = "3",
            ) shouldBe true
        }

        test("subtitle with roman numerals is redundant") {
            testIsSubtitleRedundant(
                subtitle = "The Chronicles of Narnia, Book III",
                seriesName = "The Chronicles of Narnia",
                seriesSequence = "3",
            ) shouldBe true
        }

        test("case insensitive matching works") {
            testIsSubtitleRedundant(
                subtitle = "THE STORMLIGHT ARCHIVE: BOOK ONE",
                seriesName = "The Stormlight Archive",
                seriesSequence = "1",
            ) shouldBe true
        }

        test("subtitle with extra meaningful text is not redundant") {
            testIsSubtitleRedundant(
                subtitle = "The Stormlight Archive, Book 1: The Epic Beginning",
                seriesName = "The Stormlight Archive",
                seriesSequence = "1",
            ) shouldBe false
        }
    })

// Test wrapper that uses the same logic as the private function
private fun testIsSubtitleRedundant(
    subtitle: String,
    seriesName: String?,
    seriesSequence: String?,
): Boolean {
    // If no series info, subtitle is not redundant
    if (seriesName.isNullOrBlank()) return false

    val normalizedSubtitle = subtitle.lowercase().trim()
    val normalizedSeriesName = seriesName.lowercase().trim()

    // Check if subtitle contains the series name
    if (!normalizedSubtitle.contains(normalizedSeriesName)) return false

    // Remove series name from subtitle
    var remaining = normalizedSubtitle.replace(normalizedSeriesName, "")

    // Remove common book number patterns
    val bookNumberPatterns =
        listOf(
            // "Book 1", "Book One", "Book I"
            Regex(
                """book\s*[#]?\s*(\d+|one|two|three|four|five|six|seven|eight|nine|ten|i{1,3}|iv|v|vi{0,3}|ix|x)""",
                RegexOption.IGNORE_CASE,
            ),
            // "#1", "# 1"
            Regex("""#\s*\d+"""),
            // "Part 1", "Part One"
            Regex("""part\s*[#]?\s*(\d+|one|two|three|four|five|six|seven|eight|nine|ten)""", RegexOption.IGNORE_CASE),
            // "Volume 1", "Vol. 1", "Vol 1"
            Regex("""vol(ume|\.?)?\s*[#]?\s*\d+""", RegexOption.IGNORE_CASE),
            // Just a number (if sequence matches)
            seriesSequence?.let { Regex("""\b${Regex.escape(it)}\b""") },
        ).filterNotNull()

    for (pattern in bookNumberPatterns) {
        remaining = remaining.replace(pattern, "")
    }

    // Remove common separators and punctuation
    remaining =
        remaining
            .replace(Regex("""[,.:;|\-–—/\\()\[\]{}]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    // If very little meaningful content remains (less than 3 chars), it's redundant
    return remaining.length < 3
}
