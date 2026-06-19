package com.calypsan.listenup.server.util

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.text.HexFormat

class HexEncodingTest :
    FunSpec({
        val sample = byteArrayOf(0x00, 0x0f, 0x7f, 0xff.toByte(), 0xa3.toByte(), 0x10, 0x01)

        test("toHexString matches %02x lowercase") {
            sample.toHexString() shouldBe sample.joinToString("") { "%02x".format(it) }
        }

        test("toHexString(UpperCase) matches %02X") {
            sample.toHexString(HexFormat.UpperCase) shouldBe sample.joinToString("") { "%02X".format(it) }
        }
    })
