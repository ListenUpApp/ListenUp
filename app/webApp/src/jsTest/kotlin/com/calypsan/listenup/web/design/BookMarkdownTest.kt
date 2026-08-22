package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement

private fun mount(content: @Composable () -> Unit): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    renderComposable(root = host) { content() }
    return host
}

/**
 * Book descriptions are Markdown-flavoured, and every Audible one carries it — so rendering the
 * raw string shows readers literal `**asterisks**`.
 *
 * ⛔ **The description is untrusted content from an external metadata source.** These tests exist
 * as much for that as for the formatting: the renderer builds DOM nodes and never parses an HTML
 * string, so a tag in a description cannot become an element no matter what it says. The injection
 * cases below are the ones that would matter on a page showing other people's library metadata.
 */
class BookMarkdownTest :
    FunSpec({

        test("bold and italic render as elements rather than literal syntax") {
            val host = mount { BookMarkdown("**Lead a life of adventure** and _earn a good living._") }

            host.querySelector("strong")!!.textContent shouldBe "Lead a life of adventure"
            host.querySelector("em")!!.textContent shouldBe "earn a good living."
            host.textContent!! shouldNotContain "**"
            host.textContent!! shouldNotContain "_earn"
        }

        test("a blank line starts a new paragraph") {
            val host = mount { BookMarkdown("First para.\n\nSecond para.") }

            host.querySelectorAll("p").length shouldBe 2
        }

        test("a single newline stays inside its paragraph") {
            // Publisher blurbs wrap mid-sentence; treating every newline as a break would shred them.
            val host = mount { BookMarkdown("one line\nstill the same para") }

            host.querySelectorAll("p").length shouldBe 1
        }

        test("html tags carried by an imported description are converted or stripped") {
            val host = mount { BookMarkdown("<p>A <b>bold</b> claim.<br>And a new line.</p>") }

            host.querySelector("strong")!!.textContent shouldBe "bold"
            host.textContent!! shouldNotContain "<"
            host.textContent!! shouldContain "A bold claim."
        }

        // ⛔ The injection cases. A description is written by whoever wrote the metadata, not by us.
        test("a script tag in a description cannot become an element") {
            val host = mount { BookMarkdown("Nice book.<script>alert('xss')</script>") }

            host.querySelectorAll("script").length shouldBe 0
            host.textContent!! shouldNotContain "<script"
        }

        test("an event handler on a smuggled tag cannot become an element") {
            val host = mount { BookMarkdown("""Cover: <img src=x onerror="alert(1)">""") }

            host.querySelectorAll("img").length shouldBe 0
            host.textContent!! shouldNotContain "onerror"
        }

        test("a javascript: link renders as plain text, never as an anchor") {
            val host = mount { BookMarkdown("[click me](javascript:alert(1))") }

            host.querySelectorAll("a").length shouldBe 0
            host.textContent!! shouldContain "click me"
        }

        test("an http link becomes an anchor that cannot reach back through the opener") {
            val host = mount { BookMarkdown("See [the author](https://example.com/a).") }

            val anchor = host.querySelector("a")!!
            anchor.textContent shouldBe "the author"
            anchor.getAttribute("href") shouldBe "https://example.com/a"
            anchor.getAttribute("rel")!! shouldContain "noopener"
        }

        test("an underscore inside a word is not emphasis") {
            // `snake_case_names` appear in paths and file names quoted in descriptions.
            val host = mount { BookMarkdown("the file is book_part_one.m4b") }

            host.querySelectorAll("em").length shouldBe 0
            host.textContent!! shouldContain "book_part_one.m4b"
        }

        test("an unterminated emphasis run is left as written") {
            val host = mount { BookMarkdown("2 * 3 is not emphasis") }

            host.querySelectorAll("em").length shouldBe 0
            host.textContent!! shouldContain "2 * 3 is not emphasis"
        }

        test("a description that is only whitespace renders nothing at all") {
            val host = mount { BookMarkdown("   \n\n  ") }

            host.querySelectorAll("p").length shouldBe 0
        }
    })
