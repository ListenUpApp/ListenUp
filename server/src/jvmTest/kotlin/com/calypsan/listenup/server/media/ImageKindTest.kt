package com.calypsan.listenup.server.media

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class ImageKindTest :
    FunSpec({
        test("a JPEG prefix is JPEG") {
            ImageKind.sniff(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())) shouldBe ImageKind.JPEG
        }
        test("a PNG prefix is PNG") {
            ImageKind.sniff(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A)) shouldBe ImageKind.PNG
        }
        test("a GIF prefix is GIF") {
            ImageKind.sniff("GIF89a".encodeToByteArray()) shouldBe ImageKind.GIF
        }
        test("a RIFF container tagged WEBP is WebP") {
            val webp = "RIFF".encodeToByteArray() + ByteArray(4) + "WEBP".encodeToByteArray()
            ImageKind.sniff(webp) shouldBe ImageKind.WEBP
        }
        test("a buffer shorter than four bytes is not an image") {
            ImageKind.sniff(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())).shouldBeNull()
        }
        test("RIFF without the WEBP tag is not an image") {
            val wave = "RIFF".encodeToByteArray() + ByteArray(4) + "WAVE".encodeToByteArray()
            ImageKind.sniff(wave).shouldBeNull()
        }
        test("plain text is not an image") {
            ImageKind.sniff("not an image at all".encodeToByteArray()).shouldBeNull()
        }
    })
