package com.calypsan.listenup.client.core

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The series-starter heuristic and the display formatter for a series position.
 *
 * The heuristic used to be handed text and pick the number out of it by hand. Its cases were
 * therefore strings — `"01"`, `"Book 1"`, `"Prequel"`, `"   "` — and most of them were really
 * testing the parser rather than the heuristic. The position is a number now, parsed once at the
 * ingest edge, so those inputs cannot reach here: `"01"` arrives as `1.0`, and `"Prequel"` and
 * `"   "` arrive as `null`. Each case below names the string it replaces, so the equivalence is
 * checkable rather than asserted.
 */
class SeriesSequenceTest :
    FunSpec({

        test("isFirstInSeries keeps the starter heuristic it had as a string") {
            val cases =
                listOf(
                    // was null / "" / "   " / "Prequel" / "Book 1" — everything unparseable now
                    // arrives as null, and an unknown position still counts as a starter so
                    // discovery includes rather than hides.
                    null to true,
                    0.0 to true, // was "0" — a prequel
                    0.5 to true, // was "0.5"
                    1.0 to true, // was "1", "01", "001" and "1.0", which are all one number
                    1.5 to true, // was "1.5" — an interquel is still a reasonable entry point
                    2.0 to false, // was "2"
                    2.5 to false, // was "2.5"
                    10.0 to false, // was "10" — and the string version had to work to keep this
                    11.0 to false, // was "11"
                )
            for ((input, expected) in cases) {
                withClue("isFirstInSeries($input) should be $expected") {
                    isFirstInSeries(input) shouldBe expected
                }
            }
        }

        // "Mistborn #1.0" is not how a series title is written, and interpolating a Double is
        // exactly what produces it — so the formatter, not string templating, is the display path.
        test("formatSeriesSequence drops a whole number's decimal tail but keeps a real fraction") {
            formatSeriesSequence(1.0) shouldBe "1"
            formatSeriesSequence(10.0) shouldBe "10"
            formatSeriesSequence(0.0) shouldBe "0"
            formatSeriesSequence(1.5) shouldBe "1.5"
            formatSeriesSequence(0.5) shouldBe "0.5"
            formatSeriesSequence(2.25) shouldBe "2.25"
        }

        // The round trip the book-edit screen depends on: a stored number is formatted into the
        // text field, and what comes back parses to the same number. If this ever stopped holding,
        // opening a book and saving it unchanged would silently move it in its series.
        test("a formatted sequence parses back to the number it came from") {
            for (value in listOf(0.0, 0.5, 1.0, 1.5, 2.0, 10.0, 42.75)) {
                withClue("round trip for $value") {
                    formatSeriesSequence(value).toDoubleOrNull() shouldBe value
                }
            }
        }
    })
