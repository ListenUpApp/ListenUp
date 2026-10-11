package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Planted inputs, so the rule is known to fire before it is trusted to stay quiet. */
class SourceFilesAreTextRuleSelfTest :
    FunSpec({
        test("a NUL byte is detected") {
            containsNulByte(byteArrayOf(0x61, 0x00, 0x62)) shouldBe true
        }

        test("the escape spelling of NUL is text") {
            containsNulByte("val nul = '\\u0000'".toByteArray()) shouldBe false
        }
    })
