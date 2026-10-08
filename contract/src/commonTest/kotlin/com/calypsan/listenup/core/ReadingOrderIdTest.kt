package com.calypsan.listenup.core

import com.calypsan.listenup.api.contractJson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ReadingOrderIdTest :
    FunSpec({
        test("a reading order id prints as its raw value, so it can never corrupt a column") {
            ReadingOrderId("ro-1").toString() shouldBe "ro-1"
        }

        test("a blank id is refused") {
            shouldThrow<IllegalArgumentException> { ReadingOrderId(" ") }
        }

        test("it serializes as a bare string") {
            contractJson.encodeToString(ReadingOrderId.serializer(), ReadingOrderId("ro-1")) shouldBe "\"ro-1\""
        }
    })
