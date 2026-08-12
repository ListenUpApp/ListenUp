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

private const val HEX_RADIX = 16
