package com.calypsan.listenup.server.scanner.pipeline

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

/**
 * Length bound on the description converter.
 *
 * [HtmlToMarkdown]'s anchor pattern is dot-matches-all with a lazy body, so for every opening
 * anchor with no matching close the engine scans forward to the end of the input before giving
 * up. Work therefore grows with the square of the input, and the input is a book description
 * lifted verbatim out of a scanned file — the converter has no say in how long it is. Capping
 * the length is what keeps that quadratic bounded.
 *
 * Each test carries an explicit timeout so a regression fails in seconds instead of occupying
 * the lane. Green is the proof here: without a cap the first test does not finish.
 */
class HtmlToMarkdownBoundsTest :
    FunSpec({

        test("an input far longer than any description converts in bounded time")
            .config(timeout = 10.seconds) {
                // 500,000 unterminated anchors — six million characters, which is not a
                // description of anything.
                val absurd = buildString { repeat(500_000) { append("""<a href="x">""") } }

                val converted = HtmlToMarkdown.convert(absurd)

                converted.length shouldBeLessThanOrEqual 32_768
            }

        test("a normal-length description converts unchanged")
            .config(timeout = 10.seconds) {
                HtmlToMarkdown.convert("""<p>See <a href="https://x.test">here</a>.</p>""") shouldBe
                    "See [here](https://x.test)."
            }

        test("a long-but-believable description survives the cap intact")
            .config(timeout = 10.seconds) {
                // 1,000 short paragraphs is about 15 KB — a publisher blurb at its most
                // indulgent, and well inside the cap. Nothing may be dropped.
                val blurb = buildString { repeat(1_000) { append("<p>Sentence $it.</p>") } }

                val converted = HtmlToMarkdown.convert(blurb)

                converted.startsWith("Sentence 0.") shouldBe true
                converted.endsWith("Sentence 999.") shouldBe true
            }
    })
