package com.calypsan.listenup.server.scanner.sidecar.xml

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class XmlParserTest :
    FunSpec({

        test("parses nested elements and text content") {
            val root = parseXml("<album><title>Way of Kings</title></album>")
            root.tag shouldBe "album"
            root.firstText("title") shouldBe "Way of Kings"
        }

        test("getElementsByTagName finds all descendants in document order") {
            val root = parseXml("<r><a>1</a><b><a>2</a></b><a>3</a></r>")
            root.getElementsByTagName("a").map { it.textContent } shouldBe listOf("1", "2", "3")
        }

        test("textContent concatenates nested descendant text") {
            val root = parseXml("<p>Hello <b>brave</b> world</p>")
            root.textContent shouldBe "Hello brave world"
        }

        test("reads attributes with double and single quotes") {
            val root = parseXml("""<creator role="aut" lang='en'>X</creator>""")
            root.getAttribute("role") shouldBe "aut"
            root.getAttribute("lang") shouldBe "en"
            root.getAttribute("missing") shouldBe ""
        }

        test("decodes the five predefined entities and numeric char refs") {
            val root = parseXml("<t>a &amp; b &lt; c &gt; d &quot;e&quot; &apos;f&apos; &#65; &#x42;</t>")
            root.textContent shouldBe "a & b < c > d \"e\" 'f' A B"
        }

        test("decodes entities inside attribute values") {
            val root = parseXml("""<t v="a &amp; b &#67;">x</t>""")
            root.getAttribute("v") shouldBe "a & b C"
        }

        test("decodes an astral-plane numeric char ref to a surrogate pair") {
            val root = parseXml("<t>&#x1F600;</t>")
            root.textContent shouldBe "😀"
        }

        test("passes an out-of-range numeric char ref through literally") {
            val root = parseXml("<t>&#9999999;</t>")
            root.textContent shouldBe "&#9999999;"
        }

        test("treats CDATA as literal text without entity decoding") {
            val root = parseXml("<t><![CDATA[a & b < c]]></t>")
            root.textContent shouldBe "a & b < c"
        }

        test("skips the XML declaration, comments, and DOCTYPE") {
            val root =
                parseXml(
                    """<?xml version="1.0" encoding="utf-8"?>""" +
                        "<!DOCTYPE note SYSTEM \"note.dtd\">" +
                        "<!-- a comment --><n><title>T</title></n>",
                )
            root.tag shouldBe "n"
            root.firstText("title") shouldBe "T"
        }

        test("handles self-closing elements") {
            val root = parseXml("""<r><meta name="k" content="v"/><title>T</title></r>""")
            val metas = root.getElementsByTagName("meta")
            metas shouldHaveSize 1
            metas[0].getAttribute("name") shouldBe "k"
            metas[0].getAttribute("content") shouldBe "v"
            root.firstText("title") shouldBe "T"
        }

        test("directText returns only direct text children, not nested element text") {
            val root = parseXml("<actor>Direct Name<role>Narrator</role></actor>")
            root.directText().trim() shouldBe "Direct Name"
            root.firstText("role") shouldBe "Narrator"
        }

        test("firstText returns null for an absent or blank tag") {
            val root = parseXml("<r><title>  </title></r>")
            root.firstText("title").shouldBeNull()
            root.firstText("missing").shouldBeNull()
        }

        test("allText returns trimmed text of every matching descendant, blanks dropped") {
            val root = parseXml("<r><a> x </a><a></a><a>y</a></r>")
            root.allText("a") shouldBe listOf("x", "y")
        }

        test("preserves literal prefixed tag names (non-namespace-aware)") {
            val root = parseXml("<package><dc:title>T</dc:title></package>")
            root.firstText("dc:title") shouldBe "T"
        }

        test("throws on malformed XML (mismatched tag)") {
            shouldThrowAny { parseXml("<a><b></a>") }
        }

        test("throws on malformed XML (unclosed tag at EOF)") {
            shouldThrowAny { parseXml("<a><b>") }
        }

        // Nesting depth is the one dimension of a sidecar that costs stack rather than heap, and
        // the callers catch `Exception` — which does not catch a `StackOverflowError`. On the JVM
        // that ends the scan coroutine; on the native binary it ends the process. So the reader
        // stops descending at a fixed depth and reports it as an ordinary parse failure, which
        // the callers already know how to log and skip.

        /** A document [levels] elements deep, every element named `a`, innermost one empty. */
        fun nested(levels: Int): String =
            buildString {
                repeat(levels) { append("<a>") }
                repeat(levels) { append("</a>") }
            }

        test("XML at the nesting-depth limit still parses") {
            val root = parseXml(nested(100))

            root.tag shouldBe "a"
            root.getElementsByTagName("a") shouldHaveSize 99
        }

        test("XML one level past the nesting-depth limit is rejected") {
            shouldThrow<IllegalStateException> { parseXml(nested(101)) }
        }

        test("XML nested far past the limit is rejected without descending to the stack's limit") {
            // Green here is the proof: the reader must refuse at its own depth rather than
            // recursing until the runtime stops it, so the answer is an ordinary exception the
            // sidecar parsers already handle — not an Error that escapes them.
            shouldThrow<IllegalStateException> { parseXml(nested(50_000)) }
        }

        test("traversal of a document at the depth limit returns, in document order") {
            // The DOM helpers walk the same tree the reader just built. Both must terminate on
            // the deepest document the reader will accept, and `getElementsByTagName` must still
            // answer in document order — `firstText` depends on "first" meaning first.
            val root = parseXml("<r><a>1</a><b><a>2</a></b>${nested(99)}<a>3</a></r>")

            root.textContent shouldBe "123"
            root.allText("a") shouldBe listOf("1", "2", "3")
        }
    })
