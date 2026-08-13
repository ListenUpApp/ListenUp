package com.calypsan.listenup.server.imaging

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe

/**
 * Baseline JPEG encoding — the half that turns this package from a decoder into a feature.
 *
 * **The round trip cannot compare against the source.** [decodeJpeg] is a *derivative* decoder: it
 * reconstructs only 1/8 and 1/4 scales, so encoding a 64x64 image and decoding it yields 16x16 at
 * best. The reference is therefore the source put through our own [resizedTo], which is exactly what
 * the pipeline will do in production anyway.
 *
 * **Round-tripping through our own decoder is a weaker check than the decoder's own tests get**, and
 * deliberately so: a pair of compensating bugs — an encoder that writes a wrong table and a decoder
 * that reads it back the same wrong way — would pass. What makes it trustworthy is that the decoder
 * on the other side is *independently* validated, against 1180 real covers written by encoders we
 * did not write. A bug that survives here would have to be invisible to those too.
 */
class JpegEncoderTest :
    FunSpec({

        /** Four quadrants, so a transposed block, a swapped chroma plane or a bad interleave shows. */
        fun quadrants(size: Int): PixelBuffer {
            val pixels = IntArray(size * size)
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val left = x < size / 2
                    val top = y < size / 2
                    pixels[y * size + x] =
                        when {
                            top && left -> packPixel(255, 200, 40, 40)
                            top -> packPixel(255, 40, 40, 200)
                            left -> packPixel(255, 40, 180, 40)
                            else -> packPixel(255, 230, 230, 230)
                        }
                }
            }
            return PixelBuffer(size, size, pixels)
        }

        /** JPEG is lossy, 4:2:0 discards chroma detail, and the 2x2 transform is an approximation. */
        val tolerance = 45

        fun PixelBuffer.at(
            x: Int,
            y: Int,
        ): Int = pixels[y * width + x]

        test("the stream is a well-formed JPEG") {
            val bytes = encodeJpeg(quadrants(SOURCE), quality = 85)

            withClue("must open with SOI") {
                readUByte(bytes, 0) shouldBe 0xFF
                readUByte(bytes, 1) shouldBe 0xD8
            }
            withClue("must close with EOI") {
                readUByte(bytes, bytes.size - 2) shouldBe 0xFF
                readUByte(bytes, bytes.size - 1) shouldBe 0xD9
            }
        }

        test("our own decoder reads back what we wrote, at the reduced scale") {
            val image = decodeJpeg(encodeJpeg(quadrants(SOURCE), quality = 85), maxWidth = SOURCE / 4)!!

            image.width shouldBe SOURCE / 4
            image.height shouldBe SOURCE / 4
        }

        test("a round trip preserves the image's spatial layout") {
            val decoded = decodeJpeg(encodeJpeg(quadrants(SOURCE), quality = 85), maxWidth = SOURCE / 4)!!
            val reference = quadrants(SOURCE).resizedTo(SOURCE / 4)

            // Quadrant centres, not edges: 4:2:0 shares chroma across pixel pairs, so the seams are
            // legitimately muddy and prove nothing either way.
            val quarter = decoded.width / 4
            for (y in listOf(quarter, quarter * 3)) {
                for (x in listOf(quarter, quarter * 3)) {
                    val got = decoded.at(x, y)
                    val want = reference.at(x, y)
                    withClue(
                        "at ($x,$y) expected r=${red(want)} g=${green(want)} b=${blue(want)} " +
                            "but got r=${red(got)} g=${green(got)} b=${blue(got)}",
                    ) {
                        kotlin.math.abs(red(got) - red(want)) shouldBeLessThan tolerance
                        kotlin.math.abs(green(got) - green(want)) shouldBeLessThan tolerance
                        kotlin.math.abs(blue(got) - blue(want)) shouldBeLessThan tolerance
                    }
                }
            }
        }

        // The quality knob has to actually reach the quantisation tables. A parameter that is
        // accepted and ignored is the kind of thing that only shows up as "why is storage so big".
        test("a higher quality setting spends more bytes") {
            val image = quadrants(SOURCE)

            encodeJpeg(image, quality = 95).size shouldBeGreaterThan encodeJpeg(image, quality = 40).size
        }

        test("quality is clamped rather than trusted") {
            val image = quadrants(SOURCE)

            encodeJpeg(image, quality = 0).size shouldBeGreaterThan 0
            encodeJpeg(image, quality = 1000).size shouldBeGreaterThan 0
        }

        // A cover is not always a multiple of the 16px MCU. The encoder pads to the MCU grid and the
        // frame header carries the true size, so a decoder crops back to it.
        test("a size that is not a whole number of MCUs still round-trips") {
            val odd = 40
            val bytes = encodeJpeg(quadrants(odd), quality = 85)

            val decoded = decodeJpeg(bytes, maxWidth = odd / 8)!!
            decoded.width shouldBe ceilDiv(odd, 8)
        }
    })

/** 64px: eight luma blocks across, four MCUs at 4:2:0 — enough for interleaving to be real. */
private const val SOURCE = 64
