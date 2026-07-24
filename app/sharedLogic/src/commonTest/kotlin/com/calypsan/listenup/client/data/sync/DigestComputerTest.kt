package com.calypsan.listenup.client.data.sync

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DigestComputerTest :
    FunSpec({
        test("empty rows produce count 0 and empty hash") {
            val d = DigestComputer.compute(cursor = 42L, rows = emptyList())
            d.cursor shouldBe 42L
            d.count shouldBe 0
            d.hash shouldBe ""
        }

        test("rows are sorted by id then hashed as id|rev joined by newline with trailing newline") {
            val rows = listOf("b" to 2L, "a" to 1L) // deliberately unsorted
            val d = DigestComputer.compute(cursor = 7L, rows = rows)
            d.cursor shouldBe 7L
            d.count shouldBe 2
            d.hash shouldBe "sha256:" + EXPECTED_HEX_FOR_A1_B2
        }
    })

// SHA-256 of the UTF-8 bytes of "a|1\nb|2\n" (verified: printf 'a|1\nb|2\n' | shasum -a 256)
private const val EXPECTED_HEX_FOR_A1_B2 =
    "3f98daaa5ee0487d42a1db85a6084925ad6dcbf2646e475a98921e4039bdd3d7"
