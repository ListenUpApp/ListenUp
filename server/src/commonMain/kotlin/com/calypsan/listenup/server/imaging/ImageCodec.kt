package com.calypsan.listenup.server.imaging

/**
 * The package's front door: bytes in, pixels out, whatever the format — or `null`.
 *
 * Dispatch is by **magic number, never by file extension or a declared content type**. A cover
 * arrives as bytes lifted out of an audio file's metadata, and what the tag claims is a JPEG is
 * routinely a PNG. Sniffing the first few bytes is both cheaper and more honest than believing the
 * container.
 *
 * [maxWidth] is a floor, not a target: the returned buffer is the smallest one the format can
 * cheaply produce that still covers it, and the caller resizes down precisely with [resizedTo].
 * That split exists because JPEG can decode straight to a reduced scale while PNG cannot, and the
 * caller should not have to know which it got.
 *
 * **Null is a first-class answer**, as everywhere else here: an undecodable cover keeps serving its
 * original bytes, so declining is a normal outcome rather than an error.
 */
internal fun decodeImage(
    bytes: ByteArray,
    maxWidth: Int,
): PixelBuffer? {
    require(maxWidth > 0) { "maxWidth must be positive, got $maxWidth" }
    return when (sniffFormat(bytes)) {
        ImageFormat.JPEG -> decodeJpeg(bytes, maxWidth)

        // PNG has no reduced-scale decode — it is an entropy-coded raster, not a frequency
        // decomposition — so it decodes whole and the caller downscales. Affordable because PNG
        // covers are vanishingly rare: 2 of 1195 in a real library.
        ImageFormat.PNG -> decodePng(bytes)

        null -> null
    }
}

/** The formats this package can decode. WebP is deliberately absent — see the arc's Phase 2. */
internal enum class ImageFormat {
    JPEG,
    PNG,
}

/** Identifies a format from its leading bytes, or `null` for anything we do not decode. */
internal fun sniffFormat(bytes: ByteArray): ImageFormat? =
    when {
        startsWith(bytes, JPEG_MAGIC) -> ImageFormat.JPEG
        startsWith(bytes, PNG_MAGIC) -> ImageFormat.PNG
        else -> null
    }

private fun startsWith(
    bytes: ByteArray,
    magic: ByteArray,
): Boolean = bytes.size >= magic.size && magic.indices.all { bytes[it] == magic[it] }

/** SOI. Two bytes is enough — no other format we might meet opens with them. */
private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte())

private val PNG_MAGIC =
    byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
