package com.calypsan.listenup.server.imaging

import com.calypsan.listenup.server.compression.Crc32
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

/**
 * Decodes a hex string into bytes, for image fixtures embedded in test source.
 *
 * Fixtures live in source rather than as resource files because these specs run on Kotlin/Native as
 * well as the JVM, and resource loading is not portable between them. Hex rather than
 * `byteArrayOf(...)` because the formatter expands a byte-array literal to one element per line —
 * a 120-byte PNG became 120 lines, and a JPEG fixture would be far worse.
 */
internal fun hexBytes(hex: String): ByteArray {
    val cleaned = hex.filterNot { it.isWhitespace() }
    require(cleaned.length % 2 == 0) { "hex string must have an even length, got ${cleaned.length}" }
    return ByteArray(cleaned.length / 2) { index ->
        cleaned.substring(index * 2, index * 2 + 2).toInt(HEX_RADIX).toByte()
    }
}

/**
 * A 4x5 RGBA PNG with **a different filter type on each of its five rows** — filters are where PNG
 * decoders go wrong, so one fixture exercises all of them. Written by python's zlib, an encoder we
 * did not write, so a compensating bug in our own inflate cannot make a test pass.
 */
internal val PNG_FIXTURE =
    hexBytes(
        "89504e470d0a1a0a0000000d494844520000000400000005080600000062ad4ddb0000003f4944415478da63" +
            "6038c1fe5f70b7cb1fa5758d3f8d17eefbc6a8b15ae897e067dbbf30cca4f198fb3732660ed0922a937dadf2" +
            "07865940a2829fb9ffc23000dd5c294bbe6f2e8c0000000049454e44ae426082",
    )

/**
 * A PNG that declares [width]×[height] (8-bit RGBA, non-interlaced) and carries **no image data**:
 * signature, IHDR, IEND — no IDAT at all.
 *
 * A header-only fixture for boundary tests. It separates what a file *says about itself* from what
 * it *contains*: a decoder that sizes an allocation from the header before validating it is caught
 * by the allocation, while one that validates first declines before spending anything. Built in
 * code rather than stored, like every fixture here — see [hexBytes].
 */
internal fun pngHeaderOnly(
    width: Int,
    height: Int,
): ByteArray = pngOf(width, height, idat = null)

/**
 * A PNG declaring [width]×[height] (8-bit RGBA, non-interlaced) whose single IDAT chunk holds
 * [idat] verbatim. The caller supplies the zlib stream, so it may disagree with the header on
 * purpose; `null` omits the chunk. Every chunk carries a correct CRC.
 */
internal fun pngOf(
    width: Int,
    height: Int,
    idat: ByteArray?,
): ByteArray {
    val out = Buffer()
    out.write(PNG_SIGNATURE_BYTES)
    val ihdr =
        Buffer()
            .apply {
                writeInt(width)
                writeInt(height)
                writeByte(8) // bit depth
                writeByte(6) // colour type: RGBA
                writeByte(0) // compression method
                writeByte(0) // filter method
                writeByte(0) // interlace: none
            }.readByteArray()
    out.writeChunk("IHDR", ihdr)
    idat?.let { out.writeChunk("IDAT", it) }
    out.writeChunk("IEND", ByteArray(0))
    return out.readByteArray()
}

private fun Buffer.writeChunk(
    type: String,
    data: ByteArray,
) {
    val typeBytes = type.encodeToByteArray()
    writeInt(data.size)
    write(typeBytes)
    write(data)
    val crc =
        Crc32().apply {
            update(typeBytes)
            update(data)
        }
    writeInt(crc.value.toInt())
}

/**
 * A JPEG that declares [width]×[height] — SOI, a baseline SOF0 with three 1×1-sampled components, a
 * matching SOS, EOI — and carries **no entropy-coded data** and no tables.
 *
 * Header-only in the sense a boundary test needs: the frame header is complete, so a decoder that
 * allocates from it does so, and the empty scan then declines. The SOS is present because
 * [parseJpegSegments] treats a file with no scan as not-an-image before any allocation could
 * happen — without it the fixture could never reach the code under test.
 */
internal fun jpegHeaderOnly(
    width: Int,
    height: Int,
): ByteArray {
    require(width in 1..MAX_JPEG_SIDE && height in 1..MAX_JPEG_SIDE) { "JPEG dimensions are 16-bit" }
    val out = Buffer()
    out.writeShort(SOI)

    out.writeShort(SOF0)
    out.writeShort((FRAME_HEADER_LENGTH + JPEG_COMPONENTS * FRAME_COMPONENT_BYTES).toShort())
    out.writeByte(8) // sample precision
    out.writeShort(height.toShort())
    out.writeShort(width.toShort())
    out.writeByte(JPEG_COMPONENTS.toByte())
    for (id in 1..JPEG_COMPONENTS) {
        out.writeByte(id.toByte())
        out.writeByte(SAMPLING_1X1)
        out.writeByte(0) // quantisation table
    }

    out.writeShort(SOS)
    out.writeShort((SCAN_HEADER_LENGTH + JPEG_COMPONENTS * SCAN_COMPONENT_BYTES).toShort())
    out.writeByte(JPEG_COMPONENTS.toByte())
    for (id in 1..JPEG_COMPONENTS) {
        out.writeByte(id.toByte())
        out.writeByte(0) // DC/AC table selectors
    }
    out.writeByte(0) // spectral start
    out.writeByte(LAST_COEFFICIENT) // spectral end
    out.writeByte(0) // successive approximation

    out.writeShort(EOI)
    return out.readByteArray()
}

private val PNG_SIGNATURE_BYTES =
    byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

private const val HEX_RADIX = 16
private const val MAX_JPEG_SIDE = 0xFFFF
private const val SOI: Short = 0xFFD8.toShort()
private const val SOF0: Short = 0xFFC0.toShort()
private const val SOS: Short = 0xFFDA.toShort()
private const val EOI: Short = 0xFFD9.toShort()
private const val JPEG_COMPONENTS = 3
private const val FRAME_HEADER_LENGTH = 8
private const val FRAME_COMPONENT_BYTES = 3
private const val SCAN_HEADER_LENGTH = 6
private const val SCAN_COMPONENT_BYTES = 2
private const val SAMPLING_1X1: Byte = 0x11
private const val LAST_COEFFICIENT: Byte = 63
