package com.calypsan.listenup.server.compression

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.io.Buffer

class BitIoTest :
    FunSpec({
        test("write then read arbitrary bit-widths round-trips") {
            val values = listOf(1 to 1, 0 to 1, 5 to 3, 255 to 8, 0x1FF to 9, 13 to 5, 0 to 13, 0x1FFF to 13)
            val out = Buffer()
            val w = BitWriter(out)
            for ((v, n) in values) w.writeBits(v, n)
            w.alignToByte()
            w.flush()

            val r = BitReader(out)
            for ((v, n) in values) r.readBits(n) shouldBe v
        }

        test("readBits assembles LSB-first") {
            // byte 0b1010_1100 = 0xAC; reading 4 bits then 4 bits yields the low nibble first.
            val buf = Buffer().apply { writeByte(0xAC.toByte()) }
            val r = BitReader(buf)
            r.readBits(4) shouldBe 0b1100
            r.readBits(4) shouldBe 0b1010
        }

        test("byte-aligned readBytes after some bits") {
            val out = Buffer()
            val w = BitWriter(out)
            w.writeBits(0b101, 3)
            w.alignToByte()
            w.writeBytes(byteArrayOf(1, 2, 3))
            w.flush()
            val r = BitReader(out)
            r.readBits(3) shouldBe 0b101
            r.alignToByte()
            r.readBytes(3).toList() shouldBe listOf<Byte>(1, 2, 3)
        }

        test("zero-count reads and writes are no-ops") {
            val out = Buffer()
            val w = BitWriter(out)
            w.writeBits(0, 0)
            w.writeBits(0b11, 2)
            w.alignToByte()
            w.flush()
            val r = BitReader(out)
            r.readBits(0) shouldBe 0
            r.readBits(2) shouldBe 0b11
        }

        test("readBits past end of stream throws MalformedDeflateException") {
            val r = BitReader(Buffer().apply { writeByte(0xFF.toByte()) })
            r.readBits(8) shouldBe 0xFF
            shouldThrow<MalformedDeflateException> { r.readBits(1) }
        }

        test("readBytes on a truncated stream throws MalformedDeflateException") {
            val r = BitReader(Buffer().apply { write(byteArrayOf(1, 2)) })
            shouldThrow<MalformedDeflateException> { r.readBytes(3) }
        }
    })
