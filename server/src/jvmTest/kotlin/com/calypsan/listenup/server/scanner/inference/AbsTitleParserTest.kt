package com.calypsan.listenup.server.scanner.inference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Compatibility regression net for [AbsTitleParser]. Every case here is
 * checked against the behavior of the equivalent function in ABS's
 * `server/utils/scandir.js`. Drift fails the build.
 */
class AbsTitleParserTest :
    FunSpec({

        // ========== Plain titles ==========

        test("plain title") {
            AbsTitleParser.parse("The Way of Kings") shouldBe ParsedTitle(title = "The Way of Kings")
        }

        test("trims surrounding whitespace") {
            AbsTitleParser.parse("   The Way of Kings   ") shouldBe ParsedTitle(title = "The Way of Kings")
        }

        // ========== ASIN ==========

        test("ASIN at end") {
            AbsTitleParser.parse("The Way of Kings [B0015T963C]") shouldBe
                ParsedTitle(title = "The Way of Kings", asin = "B0015T963C")
        }

        test("ASIN with surrounding spaces is collapsed") {
            AbsTitleParser.parse("Title [B0015T963C] Suffix") shouldBe
                ParsedTitle(title = "Title Suffix", asin = "B0015T963C")
        }

        test("ASIN must be 10 chars uppercase alphanumeric — lowercase rejected") {
            AbsTitleParser.parse("Title [b0015t963c]") shouldBe ParsedTitle(title = "Title [b0015t963c]")
        }

        test("ASIN must be 10 chars — 9 chars rejected") {
            AbsTitleParser.parse("Title [B001ABCDEF]") shouldBe ParsedTitle(title = "Title", asin = "B001ABCDEF")
            AbsTitleParser.parse("Title [B001ABCDE]") shouldBe ParsedTitle(title = "Title [B001ABCDE]")
        }

        // ========== Narrators ==========

        test("single narrator") {
            AbsTitleParser.parse("Title {Michael Kramer}") shouldBe
                ParsedTitle(title = "Title", narrators = listOf("Michael Kramer"))
        }

        test("two narrators comma-separated") {
            AbsTitleParser.parse("Title {Michael Kramer, Kate Reading}") shouldBe
                ParsedTitle(title = "Title", narrators = listOf("Michael Kramer", "Kate Reading"))
        }

        test("two narrators semicolon-separated") {
            AbsTitleParser.parse("Title {Michael Kramer; Kate Reading}") shouldBe
                ParsedTitle(title = "Title", narrators = listOf("Michael Kramer", "Kate Reading"))
        }

        test("two narrators with ampersand") {
            AbsTitleParser.parse("Title {Michael Kramer & Kate Reading}") shouldBe
                ParsedTitle(title = "Title", narrators = listOf("Michael Kramer", "Kate Reading"))
        }

        test("narrators with 'and'") {
            AbsTitleParser.parse("Title {Michael Kramer and Kate Reading}") shouldBe
                ParsedTitle(title = "Title", narrators = listOf("Michael Kramer", "Kate Reading"))
        }

        // ========== Year ==========

        test("year prefix with parentheses") {
            AbsTitleParser.parse("(2010) - The Way of Kings") shouldBe
                ParsedTitle(title = "The Way of Kings", publishedYear = 2010)
        }

        test("year prefix bare") {
            AbsTitleParser.parse("2010 - The Way of Kings") shouldBe
                ParsedTitle(title = "The Way of Kings", publishedYear = 2010)
        }

        // ========== Sequence (with series folder) ==========

        test("sequence: Book 2 -") {
            AbsTitleParser.parse("Book 2 - The Way of Kings", hasSeriesFolder = true) shouldBe
                ParsedTitle(title = "The Way of Kings", sequence = "2")
        }

        test("sequence: bare 2 -") {
            AbsTitleParser.parse("2 - The Way of Kings", hasSeriesFolder = true) shouldBe
                ParsedTitle(title = "The Way of Kings", sequence = "2")
        }

        test("sequence: Vol. 3 -") {
            AbsTitleParser.parse("Vol. 3 - Title Here", hasSeriesFolder = true) shouldBe
                ParsedTitle(title = "Title Here", sequence = "3")
        }

        test("sequence: Volume 12. Title - Subtitle") {
            AbsTitleParser.parse("Volume 12. Title - Subtitle", hasSeriesFolder = true) shouldBe
                ParsedTitle(title = "Title - Subtitle", sequence = "12")
        }

        test("sequence: 1.5 -") {
            AbsTitleParser.parse("1.5 - Novella", hasSeriesFolder = true) shouldBe
                ParsedTitle(title = "Novella", sequence = "1.5")
        }

        test("sequence: 0.5 -") {
            AbsTitleParser.parse("0.5 - Book Title", hasSeriesFolder = true) shouldBe
                ParsedTitle(title = "Book Title", sequence = "0.5")
        }

        test("sequence: 100 -") {
            AbsTitleParser.parse("100 - Book Title", hasSeriesFolder = true) shouldBe
                ParsedTitle(title = "Book Title", sequence = "100")
        }

        test("sequence: 6. (with trailing dot)") {
            AbsTitleParser.parse("6. Title", hasSeriesFolder = true) shouldBe
                ParsedTitle(title = "Title", sequence = "6")
        }

        test("not a sequence: '101 Dalmations' (digits + suffix without volume label or trailing dot)") {
            AbsTitleParser.parse("101 Dalmations", hasSeriesFolder = true) shouldBe
                ParsedTitle(title = "101 Dalmations")
        }

        test("sequence: '101. Dalmations' (trailing dot rescues it)") {
            AbsTitleParser.parse("101. Dalmations", hasSeriesFolder = true) shouldBe
                ParsedTitle(title = "Dalmations", sequence = "101")
        }

        test("sequence: 'Title - Subtitle - Vol 12' (sequence in trailing part)") {
            AbsTitleParser.parse("Title - Subtitle - Vol 12", hasSeriesFolder = true) shouldBe
                ParsedTitle(title = "Title - Subtitle", sequence = "12")
        }

        test("sequence ignored when no series folder") {
            AbsTitleParser.parse("Book 2 - The Way of Kings", hasSeriesFolder = false) shouldBe
                ParsedTitle(title = "Book 2 - The Way of Kings")
        }

        // ========== Subtitle ==========

        test("subtitle split on first ' - ' when parseSubtitle=true") {
            AbsTitleParser.parse("Title - Subtitle", parseSubtitle = true) shouldBe
                ParsedTitle(title = "Title", subtitle = "Subtitle")
        }

        test("subtitle keeps trailing parts together") {
            AbsTitleParser.parse("Title - Subtitle - More", parseSubtitle = true) shouldBe
                ParsedTitle(title = "Title", subtitle = "Subtitle - More")
        }

        test("subtitle off: keeps full string") {
            AbsTitleParser.parse("Title - Subtitle", parseSubtitle = false) shouldBe
                ParsedTitle(title = "Title - Subtitle")
        }

        test("subtitle off: no subtitle field even with hyphen") {
            AbsTitleParser.parse("Title - Subtitle").subtitle shouldBe null
        }

        // ========== Combinations ==========

        test("year + sequence + asin + narrators all together") {
            AbsTitleParser.parse(
                "(2010) - Book 2 - The Way of Kings [B0015T963C] {Michael Kramer}",
                hasSeriesFolder = true,
            ) shouldBe
                ParsedTitle(
                    title = "The Way of Kings",
                    publishedYear = 2010,
                    sequence = "2",
                    asin = "B0015T963C",
                    narrators = listOf("Michael Kramer"),
                )
        }

        test("year + sequence: '1980 - Book 2 - Title' (year is 4-digit, sequence regex's 0..3 digits skips it)") {
            AbsTitleParser.parse("1980 - Book 2 - Title", hasSeriesFolder = true) shouldBe
                ParsedTitle(title = "Title", publishedYear = 1980, sequence = "2")
        }

        // ========== Edge cases ==========

        test("empty input") {
            AbsTitleParser.parse("") shouldBe ParsedTitle(title = "")
        }

        test("just whitespace") {
            AbsTitleParser.parse("   ") shouldBe ParsedTitle(title = "")
        }

        test("ASIN must be at end or have surrounding spaces, not glued") {
            // The regex requires `(?: |^)` and `(?= |$)` — `[B0015T963C]Title` has no
            // separating space at the end and shouldn't match.
            AbsTitleParser.parse("[B0015T963C]Title") shouldBe ParsedTitle(title = "[B0015T963C]Title")
        }

        test("ASIN at very start") {
            AbsTitleParser.parse("[B0015T963C] The Way of Kings") shouldBe
                ParsedTitle(title = "The Way of Kings", asin = "B0015T963C")
        }
    })
