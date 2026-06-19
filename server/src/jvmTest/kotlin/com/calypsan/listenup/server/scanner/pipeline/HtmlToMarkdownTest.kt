package com.calypsan.listenup.server.scanner.pipeline

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class HtmlToMarkdownTest :
    FunSpec({
        test("plain text without HTML is returned unchanged") {
            HtmlToMarkdown.convert("A simple description.") shouldBe "A simple description."
        }
        test("paragraphs become blank-line-separated text") {
            HtmlToMarkdown.convert("<p>First.</p><p>Second.</p>") shouldBe "First.\n\nSecond."
        }
        test("bold and italic convert to Markdown") {
            HtmlToMarkdown.convert("<p>A <b>bold</b> and <i>italic</i> word.</p>") shouldBe "A **bold** and _italic_ word."
        }
        test("anchors convert to Markdown links") {
            HtmlToMarkdown.convert("""<p>See <a href="https://x.test">here</a>.</p>""") shouldBe "See [here](https://x.test)."
        }
        test("unordered list items become dashes") {
            HtmlToMarkdown.convert("<ul><li>One</li><li>Two</li></ul>") shouldBe "- One\n- Two"
        }
        test("entities are decoded") {
            HtmlToMarkdown.convert("<p>Tom &amp; Jerry &lt;3</p>") shouldBe "Tom & Jerry <3"
        }
        test("br becomes a newline") {
            HtmlToMarkdown.convert("Line one<br>Line two") shouldBe "Line one\nLine two"
        }
        test("malformed HTML does not throw and yields readable text") {
            // Unclosed emphasis tags are close-tag-tolerant: their span runs to end of input,
            // so both bold and italic survive. The exact marker placement (bold's close lands
            // after italic, since bold opened first and its span extends furthest) is the
            // converter's deterministic output — readable, never markup, never thrown.
            HtmlToMarkdown.convert("<p>Unclosed <b>bold and <i>italic") shouldBe "Unclosed **bold and _italic**_"
        }
    })
