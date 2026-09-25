package com.calypsan.listenup.server.metadata

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Tests for [providerHtmlToPlainText] — the shared HTML-to-plain-text converter for
 * provider-sourced book descriptions and author bios.
 */
class ProviderHtmlTest :
    FunSpec({

        test("plain text with no markup passes through unchanged") {
            providerHtmlToPlainText("Roshar is a world of stone and storms.") shouldBe
                "Roshar is a world of stone and storms."
        }

        test("empty input maps to empty output") {
            providerHtmlToPlainText("") shouldBe ""
        }

        test("paragraphs become separated by a blank line") {
            val html = "<p>First paragraph.</p><p>Second paragraph.</p>"
            providerHtmlToPlainText(html) shouldBe "First paragraph.\n\nSecond paragraph."
        }

        test("br tags become single newlines") {
            val html = "Line one<br>Line two<br/>Line three<br />Line four"
            providerHtmlToPlainText(html) shouldBe
                """
                Line one
                Line two
                Line three
                Line four
                """.trimIndent()
        }

        test("inline bold and italic tags do not introduce line breaks") {
            val html = "<p>Hello <b>bold</b> and <i>italic</i> text.</p>"
            providerHtmlToPlainText(html) shouldBe "Hello bold and italic text."
        }

        test("a br inside a paragraph stays a single newline while paragraphs stay blank-line separated") {
            val html = "<p>Line1<br>Line2</p><p>Second para</p>"
            providerHtmlToPlainText(html) shouldBe
                """
                Line1
                Line2

                Second para
                """.trimIndent()
        }

        test("list items each become their own line") {
            val html = "<ul><li>Apple</li><li>Banana</li></ul>"
            providerHtmlToPlainText(html) shouldBe "Apple\nBanana"
        }

        test("HTML entities are decoded") {
            val html = "<p>Fish &amp; chips &mdash; it&#39;s tasty &nbsp;&lt;really&gt;.</p>"
            providerHtmlToPlainText(html) shouldBe "Fish & chips &mdash; it's tasty  <really>."
        }

        test("tag matching is case-insensitive, including odd casing and self-closing variants") {
            val html = "<P>First</P><P>Second<BR/>Third</P>"
            providerHtmlToPlainText(html) shouldBe
                """
                First

                Second
                Third
                """.trimIndent()
        }

        test("leading and trailing whitespace is trimmed") {
            providerHtmlToPlainText("   \n  Hello world.  \n  ") shouldBe "Hello world."
        }

        test("a pre tag is not mistaken for a paragraph tag and stays one block") {
            // Standalone (no adjacent real <p> break, so a wrongly-inserted break can't hide
            // behind one that was going to happen anyway, and isn't collapsed away by the
            // 3-newline rule either — this is what makes <p[^>]*> matching <pre> visible.
            providerHtmlToPlainText("Before<pre>code</pre>After") shouldBe "BeforecodeAfter"
        }

        test("a link tag is not mistaken for a list-item tag") {
            providerHtmlToPlainText("Before<link rel=x>After") shouldBe "BeforeAfter"
        }

        test("whitespace inside the source HTML (including newlines) collapses to a single space") {
            val html = "<p>one\n   two</p><p>three</p>"
            providerHtmlToPlainText(html) shouldBe
                """
                one two

                three
                """.trimIndent()
        }

        test("a line is trimmed on both sides, not just trailing") {
            // A stray space right after the opening <p> lands, after the break substitution,
            // glued to the start of the paragraph's first real text on the same line — only a
            // leading (not just trailing) trim catches it.
            providerHtmlToPlainText("<p>a</p> <p> b</p>") shouldBe "a\n\nb"
        }

        test("a realistic multi-paragraph publisher summary preserves structure") {
            val html =
                "<p><b>From a bestselling author</b> comes an epic tale.</p>" +
                    "<p>The second paragraph continues the story, with more detail.</p>" +
                    "<p>A short third paragraph closes it out.</p>"
            providerHtmlToPlainText(html) shouldBe
                """
                From a bestselling author comes an epic tale.

                The second paragraph continues the story, with more detail.

                A short third paragraph closes it out.
                """.trimIndent()
        }
    })
