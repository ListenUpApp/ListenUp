package com.calypsan.listenup.server.imaging

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

private const val HEX_RADIX = 16
