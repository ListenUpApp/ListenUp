package com.calypsan.listenup.server.embeddedmeta.decode

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.io.Buffer

class BinaryDecoderTest : FunSpec({
    test("readBeInt24 reads three bytes big-endian") {
        val buf = Buffer().apply { write(byteArrayOf(0x00, 0x12, 0x34)) }
        buf.readBeInt24() shouldBe 0x001234
    }

    test("readSyncSafeInt parses 4 bytes with top bit always 0") {
        // ID3v2 size: 4 bytes, 7 useful bits each = 28 bits of size.
        // 257 bytes encoded as 0x00 0x00 0x02 0x01 → (2 shl 7) | 1 = 257.
        val buf = Buffer().apply { write(byteArrayOf(0x00, 0x00, 0x02, 0x01)) }
        buf.readSyncSafeInt() shouldBe 257
    }

    test("readUtf16WithBom handles BE BOM") {
        val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0x00, 0x41, 0x00, 0x42)
        val buf = Buffer().apply { write(bytes) }
        buf.readUtf16WithBom(byteCount = bytes.size) shouldBe "AB"
    }

    test("readUtf16WithBom handles LE BOM") {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x41, 0x00, 0x42, 0x00)
        val buf = Buffer().apply { write(bytes) }
        buf.readUtf16WithBom(byteCount = bytes.size) shouldBe "AB"
    }

    test("readUtf16WithBom defaults to BE when no BOM") {
        val bytes = byteArrayOf(0x00, 0x41, 0x00, 0x42)
        val buf = Buffer().apply { write(bytes) }
        buf.readUtf16WithBom(byteCount = bytes.size) shouldBe "AB"
    }

    test("readUtf8NullTerminated reads up to null byte and consumes the null") {
        val bytes = byteArrayOf(0x48, 0x69, 0x00, 0x42, 0x42)
        val buf = Buffer().apply { write(bytes) }
        buf.readUtf8NullTerminated() shouldBe "Hi"
        buf.readByte() shouldBe 0x42.toByte()
    }

    test("readPString reads length-prefixed (1-byte) UTF-8 string") {
        val bytes = byteArrayOf(0x03, 0x46, 0x6F, 0x6F)
        val buf = Buffer().apply { write(bytes) }
        buf.readPString() shouldBe "Foo"
    }
})
