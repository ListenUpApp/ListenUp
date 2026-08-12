package com.calypsan.listenup.server.imaging

import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The resize stage: area-averaging downscale over a [PixelBuffer].
 *
 * Lives in `commonTest` rather than `jvmTest` deliberately — `server.imaging` is commonMain and
 * production runs on Kotlin/Native, so a JVM-only spec would leave the shipping target unproven.
 */
class ResizeTest :
    FunSpec({

        /** A buffer where every pixel is [rgba] — the shape most invariants are easiest to see on. */
        fun flat(
            width: Int,
            height: Int,
            rgba: Int,
        ) = PixelBuffer(width, height, IntArray(width * height) { rgba })

        test("a flat colour survives a downscale unchanged") {
            val opaqueRed = 0xFF0000FF.toInt()

            val out = flat(64, 64, opaqueRed).resizedTo(maxWidth = 16)

            out.width shouldBe 16
            out.height shouldBe 16
            // Averaging a region of identical pixels must return that pixel, not drift by rounding.
            out.pixels.toSet() shouldBe setOf(opaqueRed)
        }

        test("aspect ratio is preserved on a non-square source") {
            val out = flat(400, 200, 0xFFFFFFFF.toInt()).resizedTo(maxWidth = 100)

            out.width shouldBe 100
            out.height shouldBe 50
        }

        // A cover is a fixed-resolution asset: enlarging one wastes bytes and invents detail that
        // was never there. Asking for more than the source has must return the source untouched.
        test("a request larger than the source never upscales") {
            val source = flat(120, 120, 0xFF00FF00.toInt())

            val out = source.resizedTo(maxWidth = 400)

            out.width shouldBe 120
            out.height shouldBe 120
        }

        test("a 1x1 source survives") {
            val out = flat(1, 1, 0xFF123456.toInt()).resizedTo(maxWidth = 400)

            out.width shouldBe 1
            out.height shouldBe 1
        }

        // Integer division makes it tempting to drop the trailing partial region. A cover whose
        // right-hand column vanished would be a subtle, permanent crop.
        test("an odd-dimensioned source keeps its edges") {
            val width = 33
            val height = 17
            val pixels = IntArray(width * height) { 0xFF000000.toInt() }
            // Mark the extreme bottom-right pixel — the one a truncating loop loses first.
            pixels[width * height - 1] = 0xFFFFFFFF.toInt()

            val out = PixelBuffer(width, height, pixels).resizedTo(maxWidth = 8)

            out.width shouldBe 8
            out.height shouldBe 4
            // The marked pixel must have contributed to the final destination pixel.
            val bottomRight = out.pixels[out.width * out.height - 1]
            (bottomRight and 0xFF) shouldBeGreaterThan 0
        }

        test("height never rounds down to zero on an extreme aspect ratio") {
            val out = flat(1000, 3, 0xFFFFFFFF.toInt()).resizedTo(maxWidth = 10)

            out.width shouldBe 10
            out.height shouldBe 1
        }
    })
