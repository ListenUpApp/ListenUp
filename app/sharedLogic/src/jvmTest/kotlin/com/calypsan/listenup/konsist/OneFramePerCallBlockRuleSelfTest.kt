package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Self-test for [OneFramePerCallBlockRule]'s frame counter and the hand-rolled lexer beneath it.
 *
 * **Why it exists.** The rule's own test asserts that the production tree is *clean*, so it passes
 * whether the detector works or not — a counter that had silently stopped counting would look
 * exactly like a codebase with nothing to find. That is the same shape of failure as a native lane
 * reporting green over zero discovered specs, and it earns the same answer: plant violations and
 * demand they are caught, plant safe code and demand it is not.
 *
 * The literal- and comment-stripping cases are not padding. `countServiceFrames` counts bare `it`
 * tokens, so a comment reading "it depends" or a string containing the receiver's name would each
 * invent a second RPC frame and fail an innocent PR.
 */
class OneFramePerCallBlockRuleSelfTest :
    FunSpec({

        test("counts implicit-receiver frames at the top level of the block") {
            "it.getBook(id)".countServiceFrames() shouldBe 1
            "it.getBook(id)\nit.getSeries(id)".countServiceFrames() shouldBe 2
        }

        test("counts named-receiver frames, discounting the parameter declaration") {
            "service -> service.getBook(id)".countServiceFrames() shouldBe 1
            "service -> service.getBook(id).also { service.touch() }".countServiceFrames() shouldBe 2
        }

        test("a nested lambda's implicit receiver is shadowed, so it is not a second frame") {
            "it.getBooks().map { it.title }".countServiceFrames() shouldBe 1
        }

        test("line comments never invent a frame") {
            "it.getBook(id) // it is the only frame here".countServiceFrames() shouldBe 1
        }

        test("block comments never invent a frame") {
            "it.getBook(id) /* it, it, it */".countServiceFrames() shouldBe 1
        }

        test("string literals never invent a frame") {
            """it.getBook("it")""".countServiceFrames() shouldBe 1
        }

        test("raw string literals never invent a frame") {
            // A literal `"""` cannot be written inside a raw string, so build it from a char.
            val tripleQuote = "${'"'}${'"'}${'"'}"
            "it.getBook($tripleQuote it and it $tripleQuote)".countServiceFrames() shouldBe 1
        }

        // These two carry a SECOND frame after the literal on purpose. A lexer that mis-terminates a
        // literal runs on to the end of the block looking for a close, swallowing whatever follows —
        // so the break only changes the count when there is something after the literal to lose.
        // Asserting 1 on a body whose only `it` precedes the literal passes either way, which is how
        // the first draft of this spec survived having the escape handling deleted from under it.
        test("a quote inside a char literal does not swallow the frame after it") {
            """
            it.split('"')
            it.getSeries(id)
            """.trimIndent().countServiceFrames() shouldBe 2
        }

        test("an escaped quote does not end a literal early and swallow the frame after it") {
            """
            it.getBook("a \" quote")
            it.getSeries(id)
            """.trimIndent().countServiceFrames() shouldBe 2
            """
            it.split('\'')
            it.getSeries(id)
            """.trimIndent().countServiceFrames() shouldBe 2
        }
    })
