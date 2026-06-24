package com.calypsan.listenup.server.embeddedmeta.format.mp4

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BitReaderTest :
    FunSpec({
        test("reads MSB-first bit fields across byte boundaries") {
            // 0b00010_001 , 0b1_0101_000 : first 5 bits = 2, next 4 = 3, next 4 = 5
            val r = BitReader(byteArrayOf(0b00010_001.toByte(), 0b1_0101_000.toByte()))
            r.readBits(5) shouldBe 2
            r.readBits(4) shouldBe 3
            r.readBits(4) shouldBe 5
        }
        test("reads single bits") {
            val r = BitReader(byteArrayOf(0b1010_0000.toByte()))
            r.readBits(1) shouldBe 1
            r.readBits(1) shouldBe 0
            r.readBits(1) shouldBe 1
        }
        test("returns 0 reading past end of input") {
            val r = BitReader(byteArrayOf(0xFF.toByte()))
            r.readBits(8) shouldBe 255
            r.readBits(4) shouldBe 0
        }
    })
