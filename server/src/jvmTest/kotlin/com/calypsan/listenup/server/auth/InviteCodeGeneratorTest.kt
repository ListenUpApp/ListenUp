package com.calypsan.listenup.server.auth

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class InviteCodeGeneratorTest :
    FunSpec({
        val gen = InviteCodeGenerator()

        test("generated codes are 22-char base64url (16 bytes, no padding)") {
            val code = gen.generate()
            code.length shouldBe 22
            code.all { it.isLetterOrDigit() || it == '-' || it == '_' }.shouldBeTrue()
        }

        test("100 codes are pairwise distinct") {
            val codes = (1..100).map { gen.generate() }.toSet()
            codes shouldHaveSize 100
        }
    })
