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

        // The shared fixture: 4x5 RGBA, a different filter type on every row.
        val fixture = PNG_FIXTURE

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
