package com.calypsan.listenup.server.imaging

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Format dispatch — the seam every caller of this package goes through.
 *
 * The cases that matter are the ones where the bytes disagree with what something claimed about
 * them: covers are lifted out of audio-file metadata, where a tag labelled JPEG routinely holds a
 * PNG. Sniffing is the only thing that can be trusted, so it is what is tested.
 */
class ImageCodecTest :
    FunSpec({

        val jpeg = encodeJpeg(solid(32, packPixel(255, 180, 90, 60)), quality = 85)

        test("a JPEG is recognised and decoded") {
            sniffFormat(jpeg) shouldBe ImageFormat.JPEG

            val decoded = decodeImage(jpeg, maxWidth = 8)!!
            decoded.width shouldBeGreaterThanOrEqual 8
        }

        test("a PNG is recognised and decoded whole") {
            sniffFormat(PNG_FIXTURE) shouldBe ImageFormat.PNG

            // PNG has no reduced-scale path, so it comes back at its natural size regardless.
            val decoded = decodeImage(PNG_FIXTURE, maxWidth = 1)!!
            decoded.width shouldBe 4
            decoded.height shouldBe 5
        }

        test("an unknown format declines rather than guessing") {
            sniffFormat(hexBytes("52494646")).shouldBeNull() // "RIFF" — a WebP, which we do not decode
            decodeImage(hexBytes("52494646"), maxWidth = 8).shouldBeNull()
        }

        test("empty and truncated input decline") {
            decodeImage(ByteArray(0), maxWidth = 8).shouldBeNull()
            decodeImage(jpeg.copyOfRange(0, 2), maxWidth = 8).shouldBeNull()
        }

        // Extension and declared MIME type are not consulted anywhere, and this is the reason:
        // the bytes are the only thing that has ever been reliable about an embedded cover.
        test("dispatch follows the bytes, not any label") {
            sniffFormat(PNG_FIXTURE) shouldBe ImageFormat.PNG
            sniffFormat(jpeg) shouldBe ImageFormat.JPEG
        }
    })

private fun solid(
    size: Int,
    pixel: Int,
): PixelBuffer = PixelBuffer(size, size, IntArray(size * size) { pixel })
