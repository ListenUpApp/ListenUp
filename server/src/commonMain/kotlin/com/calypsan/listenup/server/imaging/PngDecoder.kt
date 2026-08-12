package com.calypsan.listenup.server.imaging

import com.calypsan.listenup.server.compression.inflated
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.readByteArray

/**
 * Decodes an 8-bit truecolour PNG into a [PixelBuffer], or returns `null` if it cannot.
 *
 * **Null is a first-class answer.** A cover we cannot decode keeps serving its original bytes, so
 * declining is a normal outcome rather than an error: an exception escaping here would fail a
 * library scan over one odd file. Every unsupported variant and every malformed stream leaves by
 * the same door.
 *
 * Supported: colour types 2 (RGB) and 6 (RGBA) at bit depth 8, non-interlaced. Palette, greyscale,
 * 16-bit and Adam7-interlaced PNGs decline — together they are a rounding error in a cover corpus
 * (2 of 1195 files are PNG at all), and each would be dead weight until something needs it.
 */
@Suppress("ReturnCount")
internal fun decodePng(bytes: ByteArray): PixelBuffer? {
    if (!hasPngSignature(bytes)) return null

    var offset = PNG_SIGNATURE.size
    var header: PngHeader? = null
    val compressed = Buffer()

    // Chunk walk. IDAT may be split across any number of chunks and must be concatenated *before*
    // inflating — the compressed stream spans them, so decompressing each one alone would fail.
    while (offset + CHUNK_OVERHEAD <= bytes.size) {
        val length = readBigEndianInt(bytes, offset)
        if (length < 0) return null
        val type = readChunkType(bytes, offset + LENGTH_FIELD_BYTES)
        val dataStart = offset + LENGTH_FIELD_BYTES + TYPE_FIELD_BYTES
        if (dataStart + length + CRC_FIELD_BYTES > bytes.size) return null

        when (type) {
            "IHDR" -> header = parsePngHeader(bytes, dataStart, length) ?: return null
            "IDAT" -> compressed.write(bytes, dataStart, dataStart + length)
            "IEND" -> return header?.let { inflateAndUnfilter(it, compressed) }
        }

        offset = dataStart + length + CRC_FIELD_BYTES
    }

    // No IEND: the file was truncated mid-stream.
    return null
}

/** IHDR fields this decoder acts on. */
private class PngHeader(
    val width: Int,
    val height: Int,
    val channels: Int,
)

private fun parsePngHeader(
    bytes: ByteArray,
    at: Int,
    length: Int,
): PngHeader? {
    if (length < IHDR_LENGTH) return null
    val width = readBigEndianInt(bytes, at)
    val height = readBigEndianInt(bytes, at + 4)
    val bitDepth = bytes[at + 8].toInt() and 0xFF
    val colourType = bytes[at + 9].toInt() and 0xFF
    val interlace = bytes[at + 12].toInt() and 0xFF

    if (width <= 0 || height <= 0) return null
    if (bitDepth != SUPPORTED_BIT_DEPTH) return null
    if (interlace != INTERLACE_NONE) return null

    val channels =
        when (colourType) {
            COLOUR_TYPE_RGB -> RGB_CHANNELS
            COLOUR_TYPE_RGBA -> RGBA_CHANNELS
            else -> return null
        }
    return PngHeader(width, height, channels)
}

/**
 * Inflates the concatenated IDAT payload and reverses the per-scanline filters.
 *
 * IDAT carries a **zlib** stream, while our [inflated] is raw DEFLATE by design — so the 2-byte
 * zlib header is skipped here. The trailing Adler-32 is simply not read: we stop at the byte count
 * the header says the image must be, and a stream that cannot supply it throws, which is caught.
 */
@Suppress("TooGenericExceptionCaught")
private fun inflateAndUnfilter(
    header: PngHeader,
    compressed: Buffer,
): PixelBuffer? {
    val zlibHeader = ByteArray(ZLIB_HEADER_BYTES)
    return try {
        if (compressed.size < ZLIB_HEADER_BYTES) return null
        compressed.readAtMostTo(zlibHeader, 0, ZLIB_HEADER_BYTES)
        if ((zlibHeader[0].toInt() and ZLIB_METHOD_MASK) != ZLIB_DEFLATE_METHOD) return null

        val stride = header.width * header.channels
        val raw =
            compressed
                .inflated()
                .buffered()
                .readByteArray(header.height * (FILTER_BYTE + stride))

        unfilter(header, raw, stride)
    } catch (_: Exception) {
        // Truncated, corrupt, or a stream shorter than the header promised — all the same answer.
        null
    }
}

/**
 * Reverses PNG's five scanline filters in place, then packs to RGBA.
 *
 * Each filter predicts a byte from its left neighbour (`a`), the byte above (`b`) and the byte
 * above-left (`c`); reconstruction adds the prediction back. `a` and `c` are zero on the first
 * pixel of a row and `b` is zero on the first row, which is what makes the top-left corner work
 * without a special case.
 */
private fun unfilter(
    header: PngHeader,
    raw: ByteArray,
    stride: Int,
): PixelBuffer? {
    val bpp = header.channels
    val current = ByteArray(stride)
    val prior = ByteArray(stride)
    val out = IntArray(header.width * header.height)

    var read = 0
    for (y in 0 until header.height) {
        val filter = raw[read++].toInt() and 0xFF
        raw.copyInto(current, 0, read, read + stride)
        read += stride

        for (i in 0 until stride) {
            val a = if (i >= bpp) current[i - bpp].toInt() and 0xFF else 0
            val b = prior[i].toInt() and 0xFF
            val c = if (i >= bpp) prior[i - bpp].toInt() and 0xFF else 0
            val predicted =
                when (filter) {
                    FILTER_NONE -> 0
                    FILTER_SUB -> a
                    FILTER_UP -> b
                    FILTER_AVERAGE -> (a + b) / 2
                    FILTER_PAETH -> paethPredictor(a, b, c)
                    else -> return null
                }
            current[i] = ((current[i].toInt() and 0xFF) + predicted).toByte()
        }

        for (x in 0 until header.width) {
            val at = x * bpp
            out[y * header.width + x] =
                packPixel(
                    alpha = if (bpp == RGBA_CHANNELS) current[at + 3].toInt() and 0xFF else OPAQUE,
                    red = current[at].toInt() and 0xFF,
                    green = current[at + 1].toInt() and 0xFF,
                    blue = current[at + 2].toInt() and 0xFF,
                )
        }

        current.copyInto(prior)
    }

    return PixelBuffer(header.width, header.height, out)
}

/** PNG's Paeth predictor: whichever of the three neighbours is closest to `a + b - c`. */
private fun paethPredictor(
    a: Int,
    b: Int,
    c: Int,
): Int {
    val p = a + b - c
    val pa = kotlin.math.abs(p - a)
    val pb = kotlin.math.abs(p - b)
    val pc = kotlin.math.abs(p - c)
    return if (pa <= pb && pa <= pc) {
        a
    } else if (pb <= pc) {
        b
    } else {
        c
    }
}

private fun hasPngSignature(bytes: ByteArray): Boolean =
    bytes.size > PNG_SIGNATURE.size && PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }

private fun readBigEndianInt(
    bytes: ByteArray,
    at: Int,
): Int =
    ((bytes[at].toInt() and 0xFF) shl 24) or
        ((bytes[at + 1].toInt() and 0xFF) shl 16) or
        ((bytes[at + 2].toInt() and 0xFF) shl 8) or
        (bytes[at + 3].toInt() and 0xFF)

private fun readChunkType(
    bytes: ByteArray,
    at: Int,
): String = buildString { for (i in 0 until TYPE_FIELD_BYTES) append((bytes[at + i].toInt() and 0xFF).toChar()) }

private val PNG_SIGNATURE =
    byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

private const val LENGTH_FIELD_BYTES = 4
private const val TYPE_FIELD_BYTES = 4
private const val CRC_FIELD_BYTES = 4
private const val CHUNK_OVERHEAD = LENGTH_FIELD_BYTES + TYPE_FIELD_BYTES + CRC_FIELD_BYTES
private const val IHDR_LENGTH = 13
private const val SUPPORTED_BIT_DEPTH = 8
private const val INTERLACE_NONE = 0
private const val COLOUR_TYPE_RGB = 2
private const val COLOUR_TYPE_RGBA = 6
private const val RGB_CHANNELS = 3
private const val RGBA_CHANNELS = 4
private const val ZLIB_HEADER_BYTES = 2
private const val ZLIB_METHOD_MASK = 0x0F
private const val ZLIB_DEFLATE_METHOD = 8
private const val FILTER_BYTE = 1
private const val FILTER_NONE = 0
private const val FILTER_SUB = 1
private const val FILTER_UP = 2
private const val FILTER_AVERAGE = 3
private const val FILTER_PAETH = 4
private const val OPAQUE = 255
