package com.calypsan.listenup.server.metadata.itunes

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException

private const val HEADER_BYTES = 32_768

/**
 * Parses (width, height) from the leading bytes of a JPEG or PNG, or null when the data
 * is neither / too short. Pure and side-effect-free — the testable core of [ImageDimensionProbe].
 */
fun parseImageDimensions(data: ByteArray): Pair<Int, Int>? =
    parseJpegDimensions(data) ?: parsePngDimensions(data)

private fun parseJpegDimensions(data: ByteArray): Pair<Int, Int>? {
    if (data.size < 2 || data[0] != 0xFF.toByte() || data[1] != 0xD8.toByte()) return null
    var i = 2
    while (i < data.size - 9) {
        if (data[i] != 0xFF.toByte()) {
            i++
            continue
        }
        val marker = data[i + 1].toInt() and 0xFF
        // SOF0 (baseline) / SOF1 (extended) / SOF2 (progressive) carry the dimensions.
        if (marker == 0xC0 || marker == 0xC1 || marker == 0xC2) {
            if (i + 9 > data.size) return null
            val height = u16(data, i + 5)
            val width = u16(data, i + 7)
            return width to height
        }
        if (i + 3 >= data.size) break
        val segmentLen = u16(data, i + 2)
        i += 2 + segmentLen
    }
    return null
}

private fun parsePngDimensions(data: ByteArray): Pair<Int, Int>? {
    val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    if (data.size < 24 || !data.copyOfRange(0, 8).contentEquals(sig)) return null
    if (String(data.copyOfRange(12, 16), Charsets.US_ASCII) != "IHDR") return null
    val width = u32(data, 16)
    val height = u32(data, 20)
    return width to height
}

private fun u16(data: ByteArray, off: Int): Int =
    ((data[off].toInt() and 0xFF) shl 8) or (data[off + 1].toInt() and 0xFF)

private fun u32(data: ByteArray, off: Int): Int =
    ((data[off].toInt() and 0xFF) shl 24) or
        ((data[off + 1].toInt() and 0xFF) shl 16) or
        ((data[off + 2].toInt() and 0xFF) shl 8) or
        (data[off + 3].toInt() and 0xFF)

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
