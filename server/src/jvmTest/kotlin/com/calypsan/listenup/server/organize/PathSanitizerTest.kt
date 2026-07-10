@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.calypsan.listenup.server.organize

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.ascii
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

/**
 * [PathSanitizer.sanitize] pins the organizer's fixed cross-platform naming policy: strip
 * filesystem-illegal characters, collapse whitespace, trim leading/trailing dots and spaces
 * (Windows compatibility), cap segment length, and never produce an empty segment.
 */
class PathSanitizerTest :
    FunSpec({
        test("strips cross-platform-illegal characters") {
            PathSanitizer.sanitize("""AB/CD: The <Best>?""") shouldBe "ABCD The Best"
        }

        test("strips backslash, asterisk, quote, and pipe") {
            PathSanitizer.sanitize("""A\B*C"D|E""") shouldBe "ABCDE"
        }

        test("collapses runs of whitespace to a single space") {
            PathSanitizer.sanitize("The    Way   of  Kings") shouldBe "The Way of Kings"
        }

        test("collapses tabs and newlines into a single space") {
            val withMixedWhitespace = "The" + '\t' + "Way" + '\n' + "of" + '\r' + '\n' + "Kings"
            PathSanitizer.sanitize(withMixedWhitespace) shouldBe "The Way of Kings"
        }

        test("trims leading and trailing spaces") {
            PathSanitizer.sanitize("  The Way of Kings  ") shouldBe "The Way of Kings"
        }

        test("trims leading and trailing dots (Windows compatibility)") {
            PathSanitizer.sanitize("...The Way of Kings...") shouldBe "The Way of Kings"
        }

        test("trims a mix of trailing dots and spaces") {
            PathSanitizer.sanitize("The Way of Kings . . ") shouldBe "The Way of Kings"
        }

        test("preserves interior dots") {
            PathSanitizer.sanitize("Mr. Penumbra's 24-Hour Bookstore") shouldBe "Mr. Penumbra's 24-Hour Bookstore"
        }

        test("300-char title is truncated to the 120-char segment cap") {
            val title = "A".repeat(300)
            val result = PathSanitizer.sanitize(title)
            result.length shouldBe 120
            result shouldBe "A".repeat(120)
        }

        test("truncation re-trims a trailing space or dot left by the hard cut") {
            // Construct a string whose 120th character is a space, so a naive substring(0, 120)
            // would leave a trailing space that the cap must clean up.
            val title = "A".repeat(119) + " " + "B".repeat(200)
            val result = PathSanitizer.sanitize(title)
            result.last() shouldBe 'A'
            (result.length <= 120) shouldBe true
        }

        test("empty input falls back to Untitled") {
            PathSanitizer.sanitize("") shouldBe "Untitled"
        }

        test("whitespace-only input falls back to Untitled") {
            PathSanitizer.sanitize("   ") shouldBe "Untitled"
        }

        test("all-illegal-characters input falls back to Untitled") {
            PathSanitizer.sanitize("""/\:*?"<>|""") shouldBe "Untitled"
        }

        test("all-dots-and-spaces input falls back to Untitled") {
            PathSanitizer.sanitize(" . . . ") shouldBe "Untitled"
        }

        test("is idempotent on a fixed pinned example") {
            val once = PathSanitizer.sanitize("""AB/CD: The <Best>?""")
            PathSanitizer.sanitize(once) shouldBe once
        }

        test("property: output never contains an illegal character") {
            checkAll(PropTestConfig(iterations = 300), arbitraryInput) { input ->
                val result = PathSanitizer.sanitize(input)
                result.none { it in ILLEGAL_CHARS_UNDER_TEST } shouldBe true
            }
        }

        test("property: output is never empty") {
            checkAll(PropTestConfig(iterations = 300), arbitraryInput) { input ->
                PathSanitizer.sanitize(input).shouldNotBeEmpty()
            }
        }

        test("property: output never exceeds the segment length cap") {
            checkAll(PropTestConfig(iterations = 300), arbitraryInput) { input ->
                (PathSanitizer.sanitize(input).length <= 120) shouldBe true
            }
        }

        test("property: output never starts or ends with a dot or space") {
            checkAll(PropTestConfig(iterations = 300), arbitraryInput) { input ->
                val result = PathSanitizer.sanitize(input)
                if (result != "Untitled") {
                    (result.first() != ' ' && result.first() != '.') shouldBe true
                    (result.last() != ' ' && result.last() != '.') shouldBe true
                }
            }
        }

        test("property: sanitize is idempotent — sanitize(sanitize(x)) == sanitize(x)") {
            checkAll(PropTestConfig(iterations = 300), arbitraryInput) { input ->
                val once = PathSanitizer.sanitize(input)
                val twice = PathSanitizer.sanitize(once)
                twice shouldBe once
            }
        }
    })

private val ILLEGAL_CHARS_UNDER_TEST = charArrayOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')

// Wide codepoint range (control chars, punctuation, unicode, illegal chars) to stress every
// branch of the sanitizer — narrower generators wouldn't exercise the illegal-char stripping or
// the empty-after-sanitize fallback.
private val arbitraryInput: Arb<String> =
    Arb.string(minSize = 0, maxSize = 400, codepoints = Codepoint.ascii())
