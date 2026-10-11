package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Self-test for [stripComments], the helper five rules read source text through.
 *
 * A stripper that deletes the tail of a line after the `//` inside `"https://x"` hides real code from
 * the rule — a rule that then reports green over code it never saw. Comments must go; literals must
 * survive untouched.
 */
class StripCommentsSelfTest :
    FunSpec({

        test("a line comment is removed") {
            val out = stripComments("a() // b()")
            out shouldContain "a()"
            out shouldNotContain "b()"
        }

        test("a block comment is removed") {
            val out = stripComments("a() /* b() */ c()")
            out shouldContain "a()"
            out shouldContain "c()"
            out shouldNotContain "b()"
        }

        test("a // inside a string literal is not a comment") {
            val out = stripComments("""val u = "https://x"; register()""")
            out shouldContain "register()"
            out shouldContain "\"https://x\""
        }

        test("a block-comment opener inside a string literal is not a comment") {
            val out = stripComments("""val s = "/* not a comment */"; keep()""")
            out shouldContain "keep()"
            out shouldContain "\"/* not a comment */\""
        }

        test("a // inside a raw string literal is not a comment") {
            // A literal `"""` cannot be written inside a raw string, so build it from a char.
            val tripleQuote = "${'"'}${'"'}${'"'}"
            val out = stripComments("val s = $tripleQuote// not a comment$tripleQuote; keep()")
            out shouldContain "keep()"
            out shouldContain "// not a comment"
        }

        test("a quote inside a char literal does not swallow a following comment") {
            val out = stripComments("""val c = '"'; keep() // gone()""")
            out shouldContain "keep()"
            out shouldNotContain "gone()"
        }

        test("an unterminated block comment does not throw") {
            stripComments("a() /* never closed") shouldBe "a() "
        }
    })
