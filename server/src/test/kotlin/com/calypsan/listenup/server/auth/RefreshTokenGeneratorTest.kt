package com.calypsan.listenup.server.auth

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class RefreshTokenGeneratorTest :
    FunSpec({
        val gen = RefreshTokenGenerator()

        test("generated tokens are 43-char base64url (32 bytes, no padding)") {
            val token = gen.generate()
            token.length shouldBe 43
            token.all { it.isLetterOrDigit() || it == '-' || it == '_' }.shouldBeTrue()
        }

        test("100 tokens are pairwise distinct") {
            val tokens = (1..100).map { gen.generate() }.toSet()
            tokens shouldHaveSize 100
        }
    })
