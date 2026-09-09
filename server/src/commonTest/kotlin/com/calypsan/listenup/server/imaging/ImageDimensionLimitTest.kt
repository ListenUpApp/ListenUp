package com.calypsan.listenup.server.imaging

import com.calypsan.listenup.server.compression.deflated
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.readByteArray

/**
 * The ceiling on what a header can ask for.
 *
 * Both decoders learn an image's dimensions from a handful of header bytes and size their working
 * memory from them — so the file, not its content, decides the allocation. These specs hand the
 * decoders headers that declare far more pixels than any cover has and expect a cheap decline: no
 * allocation, no `Error` escaping the "null is a first-class answer" contract. The fixtures are
 * header-only by construction ([pngHeaderOnly], [jpegHeaderOnly]) so the only thing the decoder can
 * act on is the declaration.
 */
class ImageDimensionLimitTest :
    FunSpec({

        test("a PNG whose header declares more pixels than the cap declines") {
            decodePng(pngHeaderOnly(OVER_CAP_SIDE, OVER_CAP_SIDE)).shouldBeNull()
        }

        // Before the cap this fixture asked for gigabytes of coefficients before a single scan byte
        // was read, and the OutOfMemoryError escaped the decoder's catch (an Error, not an Exception).
        test("a JPEG whose header declares more pixels than the cap declines before allocating") {
            decodeJpeg(jpegHeaderOnly(OVER_CAP_SIDE, OVER_CAP_SIDE), maxWidth = 300).shouldBeNull()
        }

        // The frame parser is where the JPEG cap lives, and it allocates nothing — so the boundary
        // can be pinned exactly: the cap is inclusive, and one more pixel declines.
        test("the JPEG cap is inclusive at exactly MAX_DECODABLE_PIXELS") {
            val height = (MAX_DECODABLE_PIXELS / AT_CAP_WIDTH).toInt()
            check(AT_CAP_WIDTH.toLong() * height == MAX_DECODABLE_PIXELS) { "fixture must sit exactly on the cap" }

            parseJpegSegments(jpegHeaderOnly(AT_CAP_WIDTH, height)).shouldNotBeNull()
            parseJpegSegments(jpegHeaderOnly(AT_CAP_WIDTH, height + 1)).shouldBeNull()
        }

        // The header is within the cap, so the pixel check passes; the IDAT is the attacker's second
        // lever. Its stream inflates to thousands of times the 18-byte raster a 2×2 image needs, and
        // the decoder must stop at what the header promised rather than buffer whatever arrives.
        test("a PNG whose IDAT inflates past the raster its header declares declines rather than throwing") {
            val idat = zlibStreamOfZeros(OVER_DECLARED_IDAT_BYTES)

            decodePng(pngOf(2, 2, idat)).shouldBeNull()
        }

        // The regression guard: the cap must never bite a legitimate cover. The env-gated
        // JpegCorpusTest is the real check over a library; this is the hermetic stand-in.
        test("a legitimate cover still decodes") {
            decodePng(PNG_FIXTURE).shouldNotBeNull()

            val cover = encodeJpeg(PixelBuffer(COVER_SIDE, COVER_SIDE, IntArray(COVER_SIDE * COVER_SIDE) { COVER_PIXEL }), quality = 85)
            decodeJpeg(cover, maxWidth = COVER_SIDE / 8).shouldNotBeNull()
        }
    })

/**
 * A zlib stream — the two-byte header PNG requires, then our own raw DEFLATE — of [count] zero bytes.
 * Zeros are also valid PNG rows (filter type None), so the only thing wrong with the result is how
 * much of it there is.
 */
private fun zlibStreamOfZeros(count: Int): ByteArray {
    val out = Buffer()
    out.writeByte(ZLIB_CMF)
    out.writeByte(ZLIB_FLG)
    out.deflated().buffered().use { it.write(ByteArray(count)) }
    return out.readByteArray()
}

/** 1.6 gigapixels: 32× the cap, and past any heap a test worker has, so an allocation cannot pass unnoticed. */
private const val OVER_CAP_SIDE = 40_000
private const val OVER_DECLARED_IDAT_BYTES = 64 * 1024

/** zlib header: CM = 8 (deflate), CINFO = 7; FLG chosen so the pair is a multiple of 31. */
private const val ZLIB_CMF: Byte = 0x78
private const val ZLIB_FLG: Byte = 0x9C.toByte()

/** Divides the cap exactly, so the at-cap fixture sits on the boundary rather than beside it. */
private const val AT_CAP_WIDTH = 5_000
private const val COVER_SIDE = 512
private val COVER_PIXEL = packPixel(255, 180, 90, 60)
