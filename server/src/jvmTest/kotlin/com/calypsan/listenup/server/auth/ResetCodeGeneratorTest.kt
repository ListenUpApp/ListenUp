package com.calypsan.listenup.server.auth

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch

class ResetCodeGeneratorTest :
    FunSpec({
        val generator = ResetCodeGenerator()

        test("generate returns 8 chars with no separator") {
            generator.generate() shouldMatch Regex("[0-9A-HJKMNP-TV-Z]{8}")
        }

        test("format grouped as XXXX-XXXX") {
            ResetCodeGenerator.format(generator.generate()) shouldMatch Regex("[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}")
        }

        test("the alphabet excludes characters confused when read aloud") {
            val codes = List(200) { generator.generate() }.joinToString("")
            listOf('I', 'L', 'O', 'U').forEach { codes.contains(it) shouldBe false }
        }

        test("codes are not repeated across a large sample") {
            List(500) { generator.generate() }.toSet() shouldHaveSize 500
        }

        test("normalize accepts lowercase, missing dash, and surrounding space") {
            ResetCodeGenerator.normalize(" abcd-2345 ") shouldBe "ABCD2345"
            ResetCodeGenerator.normalize("ABCD2345") shouldBe "ABCD2345"
        }

        test("normalize accepts a space instead of the dash") {
            ResetCodeGenerator.normalize("ABCD 2345") shouldBe "ABCD2345"
        }

        test("normalize accepts an en-dash") {
            ResetCodeGenerator.normalize("ABCD–2345") shouldBe "ABCD2345"
        }

        test("normalize drops trailing punctuation") {
            ResetCodeGenerator.normalize("abcd-2345.") shouldBe "ABCD2345"
        }

        test("format then normalize returns the canonical code — display never changes the value") {
            repeat(50) {
                val raw = generator.generate()
                ResetCodeGenerator.normalize(ResetCodeGenerator.format(raw)) shouldBe raw
            }
        }
    })
