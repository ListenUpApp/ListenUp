package com.calypsan.listenup.domain.readingorder

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ReadingOrderNameTest :
    FunSpec({
        test("a valid name is trimmed") {
            ReadingOrderName.validate("  Ultimate Read Order ") shouldBe "Ultimate Read Order"
        }

        test("blank and over-80 names are invalid; exactly 80 is fine") {
            ReadingOrderName.validate("   ") shouldBe null
            ReadingOrderName.validate("x".repeat(81)) shouldBe null
            ReadingOrderName.validate("x".repeat(80)) shouldBe "x".repeat(80)
            ReadingOrderName.validate("  " + "x".repeat(80) + "  ") shouldBe "x".repeat(80)
        }

        test("names that differ only in case and spacing normalize alike") {
            ReadingOrderName.normalize("Ultimate  Read order") shouldBe
                ReadingOrderName.normalize(" ultimate read ORDER")
        }

        test("names that differ in words do not") {
            (ReadingOrderName.normalize("Read Order") == ReadingOrderName.normalize("Reading Order")) shouldBe false
        }
    })
