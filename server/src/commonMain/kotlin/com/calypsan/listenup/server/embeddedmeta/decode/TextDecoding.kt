package com.calypsan.listenup.server.embeddedmeta.decode

private const val BYTE_MASK = 0xFF
private const val BITS_PER_BYTE = 8

/**
 * Byte→text decoding for the charsets the audio-metadata formats use, in pure commonMain Kotlin
 * so the parsers decode identically on JVM and native. A Kotlin `String`/`Char` is UTF-16 internally,
 * so ISO-8859-1 and UTF-16 decode without any platform charset API (Kotlin/Native ships only
 * `Charsets.UTF_8`). UTF-8 callers use [kotlin.text.decodeToString] directly.
 */
internal object TextDecoding {
    /** ISO-8859-1 (Latin-1): each byte is the code point of the same value. */
    fun decodeLatin1(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ): String {
        val chars = CharArray(length) { i -> (bytes[offset + i].toInt() and BYTE_MASK).toChar() }
        return chars.concatToString()
    }

    /**
     * UTF-16: each pair of bytes is one 16-bit code unit (a Kotlin [Char]); [bigEndian] selects the
     * byte order. Surrogate pairs round-trip (Kotlin stores them as two chars). A trailing odd byte
     * is ignored — matching the well-formed inputs real tags carry.
     */
    fun decodeUtf16(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
        bigEndian: Boolean,
    ): String {
        val unitCount = length / 2
        val chars =
            CharArray(unitCount) { i ->
                val hiIdx = offset + i * 2
                val firstByte = bytes[hiIdx].toInt() and BYTE_MASK
                val secondByte = bytes[hiIdx + 1].toInt() and BYTE_MASK
                // BE: first byte is the high byte; LE: first byte is the low byte.
                val code =
                    if (bigEndian) {
                        (firstByte shl BITS_PER_BYTE) or secondByte
                    } else {
                        (secondByte shl BITS_PER_BYTE) or firstByte
                    }
                code.toChar()
            }
        return chars.concatToString()
    }
}
