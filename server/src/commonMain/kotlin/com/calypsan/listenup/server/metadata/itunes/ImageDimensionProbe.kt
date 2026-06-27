package com.calypsan.listenup.server.metadata.itunes

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException

private const val HEADER_BYTES = 32_768
private const val BYTE_MASK = 0xFF

// JPEG: marker prefix 0xFF; SOI 0xD8; SOF0/1/2 carry dimensions. Within an SOF segment the height
// is at marker+5 and width at marker+7, so marker+9 bytes must be available.
private const val JPEG_MARKER = 0xFF
private const val JPEG_SOI = 0xD8
private const val JPEG_SOF0 = 0xC0
private const val JPEG_SOF1 = 0xC1
private const val JPEG_SOF2 = 0xC2
private const val JPEG_SOF_BYTES_NEEDED = 9
private const val JPEG_HEIGHT_OFFSET = 5
private const val JPEG_WIDTH_OFFSET = 7
private const val JPEG_SEGMENT_LEN_OFFSET = 2
private const val JPEG_MARKER_BYTE_OFFSET = 1
private const val JPEG_HEADER_START = 2

// PNG: 8-byte signature, then a length(4) + "IHDR"(4) chunk; width at byte 16, height at byte 20.
private const val PNG_SIG_SIZE = 8
private const val PNG_MIN_BYTES = 24
private const val PNG_IHDR_LABEL_START = 12
private const val PNG_IHDR_LABEL_END = 16
private const val PNG_WIDTH_OFFSET = 16
private const val PNG_HEIGHT_OFFSET = 20

private const val SHIFT_8 = 8
private const val SHIFT_16 = 16
private const val SHIFT_24 = 24

private val PNG_SIGNATURE =
    byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

/**
 * Parses (width, height) from the leading bytes of a JPEG or PNG, or null when the data
 * is neither / too short. Pure and side-effect-free — the testable core of [ImageDimensionProbe].
 */
fun parseImageDimensions(data: ByteArray): Pair<Int, Int>? = parseJpegDimensions(data) ?: parsePngDimensions(data)

private fun parseJpegDimensions(data: ByteArray): Pair<Int, Int>? {
    if (data.size < JPEG_HEADER_START ||
        data[0] != JPEG_MARKER.toByte() ||
        data[1] != JPEG_SOI.toByte()
    ) {
        return null
    }
    var i = JPEG_HEADER_START
    while (i < data.size - JPEG_SOF_BYTES_NEEDED) {
        if (data[i] != JPEG_MARKER.toByte()) {
            i++
            continue
        }
        val marker = data[i + JPEG_MARKER_BYTE_OFFSET].toInt() and BYTE_MASK
        if (marker == JPEG_SOF0 || marker == JPEG_SOF1 || marker == JPEG_SOF2) {
            if (i + JPEG_SOF_BYTES_NEEDED > data.size) return null
            val height = u16(data, i + JPEG_HEIGHT_OFFSET)
            val width = u16(data, i + JPEG_WIDTH_OFFSET)
            return width to height
        }
        if (i + JPEG_SEGMENT_LEN_OFFSET + JPEG_MARKER_BYTE_OFFSET >= data.size) break
        val segmentLen = u16(data, i + JPEG_SEGMENT_LEN_OFFSET)
        i += JPEG_SEGMENT_LEN_OFFSET + segmentLen
    }
    return null
}

private fun parsePngDimensions(data: ByteArray): Pair<Int, Int>? {
    if (data.size < PNG_MIN_BYTES || !data.copyOfRange(0, PNG_SIG_SIZE).contentEquals(PNG_SIGNATURE)) {
        return null
    }
    if (data.copyOfRange(PNG_IHDR_LABEL_START, PNG_IHDR_LABEL_END).decodeToString() != "IHDR") {
        return null
    }
    val width = u32(data, PNG_WIDTH_OFFSET)
    val height = u32(data, PNG_HEIGHT_OFFSET)
    return width to height
}

private fun u16(
    data: ByteArray,
    off: Int,
): Int = ((data[off].toInt() and BYTE_MASK) shl SHIFT_8) or (data[off + 1].toInt() and BYTE_MASK)

private fun u32(
    data: ByteArray,
    off: Int,
): Int =
    ((data[off].toInt() and BYTE_MASK) shl SHIFT_24) or
        ((data[off + 1].toInt() and BYTE_MASK) shl SHIFT_16) or
        ((data[off + 2].toInt() and BYTE_MASK) shl SHIFT_8) or
        (data[off + 3].toInt() and BYTE_MASK)

/**
 * Fetches just an image's leading bytes via an HTTP Range request and parses its
 * dimensions. Returns null on any network/parse failure (callers degrade to 0×0) —
 * a missing dimension must never drop an otherwise-usable cover. Re-raises [CancellationException].
 */
class ImageDimensionProbe(
    private val httpClient: HttpClient,
) {
    suspend fun probe(url: String): Pair<Int, Int>? {
        if (url.isBlank()) return null
        return try {
            val bytes =
                httpClient
                    .get(url) { header(HttpHeaders.Range, "bytes=0-${HEADER_BYTES - 1}") }
                    .bodyAsBytes()
            parseImageDimensions(if (bytes.size > HEADER_BYTES) bytes.copyOf(HEADER_BYTES) else bytes)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}
