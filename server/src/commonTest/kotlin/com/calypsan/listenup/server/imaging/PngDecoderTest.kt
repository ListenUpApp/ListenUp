package com.calypsan.listenup.server.imaging

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * PNG decoding, built on our own inflate.
 *
 * The fixture is a 4x5 RGBA image whose **rows each use a different PNG filter type** — None, Sub,
 * Up, Average and Paeth in order. That is the point of it: filters are where a PNG decoder goes
 * wrong, and a fixture written by a typical encoder would exercise whichever one or two that
 * encoder happened to pick. It was produced by an independent encoder (python's zlib), not by our
 * own [com.calypsan.listenup.server.compression.deflated], so a compensating pair of bugs in our
 * deflate and our inflate cannot make this pass.
 */
class PngDecoderTest :
    FunSpec({

        // 4x5 RGBA, one filter type per row.
        val fixture =
            byteArrayOf(
                0x89.toByte(),
                0x50,
                0x4E,
                0x47,
                0x0D,
                0x0A,
                0x1A,
                0x0A,
                0x00,
                0x00,
                0x00,
                0x0D,
                0x49,
                0x48,
                0x44,
                0x52,
                0x00,
                0x00,
                0x00,
                0x04,
                0x00,
                0x00,
                0x00,
                0x05,
                0x08,
                0x06,
                0x00,
                0x00,
                0x00,
                0x62,
                0xAD.toByte(),
                0x4D,
                0xDB.toByte(),
                0x00,
                0x00,
                0x00,
                0x3F,
                0x49,
                0x44,
                0x41,
                0x54,
                0x78,
                0xDA.toByte(),
                0x63,
                0x60,
                0x38,
                0xC1.toByte(),
                0xFE.toByte(),
                0x5F,
                0x70,
                0xB7.toByte(),
                0xCB.toByte(),
                0x1F,
                0xA5.toByte(),
                0x75,
                0x8D.toByte(),
                0x3F,
                0x8D.toByte(),
                0x17,
                0xEE.toByte(),
                0xFB.toByte(),
                0xC6.toByte(),
                0xA8.toByte(),
                0xB1.toByte(),
                0x5A,
                0xE8.toByte(),
                0x97.toByte(),
                0xE0.toByte(),
                0x67,
                0xDB.toByte(),
                0xBF.toByte(),
                0x30,
                0xCC.toByte(),
                0xA4.toByte(),
                0xF1.toByte(),
                0x98.toByte(),
                0xFB.toByte(),
                0x37,
                0x32,
                0x66,
                0x0E,
                0xD0.toByte(),
                0x92.toByte(),
                0x2A,
                0x93.toByte(),
                0x7D,
                0xAD.toByte(),
                0xF2.toByte(),
                0x07,
                0x86.toByte(),
                0x59,
                0x40,
                0xA2.toByte(),
                0x82.toByte(),
                0x9F.toByte(),
                0xB9.toByte(),
                0xFF.toByte(),
                0xC2.toByte(),
                0x30,
                0x00,
                0xDD.toByte(),
                0x5C,
                0x29,
                0x4B,
                0xBE.toByte(),
                0x6F,
                0x2E,
                0x8C.toByte(),
                0x00,
                0x00,
                0x00,
                0x00,
                0x49,
                0x45,
                0x4E,
                0x44,
                0xAE.toByte(),
                0x42,
                0x60,
                0x82.toByte(),
            )

        /** Row-major expected pixels, as (r, g, b, a). */
        fun expected(
            x: Int,
            y: Int,
        ): List<Int> =
            listOf(
                (17 * x + 40 * y) % 256,
                (200 - 13 * x - 29 * y).mod(256),
                (7 + 61 * x + 11 * y) % 256,
                255 - (x * 3 + y * 5),
            )

        test("decodes dimensions") {
            val image = decodePng(fixture)!!

            image.width shouldBe 4
            image.height shouldBe 5
        }

        test("every filter type reconstructs its row exactly") {
            val image = decodePng(fixture)!!

            for (y in 0 until 5) {
                for (x in 0 until 4) {
                    val pixel = image.pixels[y * image.width + x]
                    val actual = listOf(red(pixel), green(pixel), blue(pixel), alpha(pixel))
                    withClue("pixel ($x,$y) — filter type $y") {
                        actual shouldBe expected(x, y)
                    }
                }
            }
        }

        test("a truncated file declines rather than throwing") {
            decodePng(fixture.copyOfRange(0, 40)).shouldBeNull()
        }

        test("a file that is not a PNG declines") {
            // A JPEG's SOI marker — the format this decoder will most often be handed by mistake.
            decodePng(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())).shouldBeNull()
        }
    })
