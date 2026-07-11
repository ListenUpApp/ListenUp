package com.calypsan.listenup.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ReadingOrderIdTest :
    FunSpec({
        test("wraps a non-blank value") {
            ReadingOrderId("ro-1").value shouldBe "ro-1"
        }
        test("rejects a blank value") {
            shouldThrow<IllegalArgumentException> { ReadingOrderId("  ") }
        }
    })
