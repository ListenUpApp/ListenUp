package com.calypsan.listenup.server.embeddedmeta.decode

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TextDecodingTest :
    FunSpec({

        test("decodeLatin1 maps each byte to its own code point") {
            // "Hello" + é (0xE9 = U+00E9)
            val bytes = byteArrayOf(0x48, 0x65, 0x6C, 0x6C, 0x6F, 0xE9.toByte())
            TextDecoding.decodeLatin1(bytes) shouldBe "Helloé"
        }

        test("decodeLatin1 honors offset and length") {
            val bytes = byteArrayOf(0x00, 0x41, 0x42, 0x00)
            TextDecoding.decodeLatin1(bytes, offset = 1, length = 2) shouldBe "AB"
        }

        test("decodeLatin1 decodes a high byte inside a non-zero-offset window") {
            // 0xE9 = U+00E9 (é); exercises the sign-extension path away from offset 0.
            val bytes = byteArrayOf(0x00, 0x41, 0xE9.toByte(), 0x00)
            TextDecoding.decodeLatin1(bytes, offset = 1, length = 2) shouldBe "Aé"
        }

        test("decodeUtf16 big-endian decodes a 2-byte unit per char") {
            val bytes = byteArrayOf(0x00, 0x41, 0x00, 0x42) // "AB" UTF-16BE
            TextDecoding.decodeUtf16(bytes, bigEndian = true) shouldBe "AB"
        }

        test("decodeUtf16 little-endian decodes a 2-byte unit per char") {
            val bytes = byteArrayOf(0x41, 0x00, 0x42, 0x00) // "AB" UTF-16LE
            TextDecoding.decodeUtf16(bytes, bigEndian = false) shouldBe "AB"
        }

        test("decodeUtf16 preserves a non-BMP surrogate pair") {
            // U+1F600 😀 → surrogate pair D83D DE00, big-endian bytes
            val bytes = byteArrayOf(0xD8.toByte(), 0x3D, 0xDE.toByte(), 0x00)
            TextDecoding.decodeUtf16(bytes, bigEndian = true) shouldBe "😀"
        }

        test("decodeUtf16 honors offset and length and ignores a trailing odd byte") {
            val bytes = byteArrayOf(0x00, 0x00, 0x00, 0x41, 0x00, 0x42, 0x7F)
            TextDecoding.decodeUtf16(bytes, offset = 2, length = 5, bigEndian = true) shouldBe "AB"
        }
    })
