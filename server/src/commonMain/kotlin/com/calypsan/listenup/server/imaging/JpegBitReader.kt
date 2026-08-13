package com.calypsan.listenup.server.imaging

/**
 * Bit-level reader over a JPEG entropy-coded segment.
 *
 * JPEG is MSB-first and **byte-stuffed**: a literal `0xFF` in the data is written as `0xFF 0x00`,
 * so the reader must swallow that second byte. This is why `server.compression`'s `BitIo` is not
 * reused — DEFLATE is LSB-first with no stuffing, and the two conventions are not a flag apart.
 */
internal class JpegBitReader(
    private val bytes: ByteArray,
    private var offset: Int,
    private val end: Int,
) {
    private var bitBuffer = 0
    private var bitCount = 0

    /** Progressive AC scans code runs of all-zero blocks; this is the remaining run length. */
    var endOfBandRun = 0

    /** Reads one bit, returning 0 past the end so a truncated tail decays instead of throwing. */
    fun readBit(): Int {
        if (bitCount == 0) {
            if (offset >= end) return 0
            var byte = readUByte(bytes, offset++)
            if (byte == MARKER_PREFIX) {
                val next = if (offset < end) readUByte(bytes, offset) else 0
                if (next == 0) {
                    offset++ // stuffed byte
                } else {
                    // A real marker inside the data: the segment is over.
                    byte = 0
                }
            }
            bitBuffer = byte
            bitCount = BITS_PER_BYTE
        }
        bitCount--
        return (bitBuffer shr bitCount) and 1
    }

    fun readBits(count: Int): Int {
        var value = 0
        repeat(count) { value = (value shl 1) or readBit() }
        return value
    }

    /** Walks canonical Huffman codes one bit at a time until a code length matches. */
    fun decodeHuffman(table: JpegHuffmanTable): Int? {
        var code = 0
        for (length in 1..MAX_CODE_LENGTH) {
            code = (code shl 1) or readBit()
            val max = table.maxCode[length]
            if (max >= 0 && code <= max) {
                val index = table.valuePointer[length] + (code - table.minCode[length])
                return table.values.getOrNull(index)
            }
        }
        return null
    }

    /**
     * Reads a [magnitude]-bit value and sign-extends it, per the JPEG "receive and extend" rule:
     * values in the bottom half of the band are negative, offset by the band's range.
     */
    fun receiveExtend(magnitude: Int): Int {
        val value = readBits(magnitude)
        val range = 1 shl magnitude
        return if (value * 2 < range) value - range + 1 else value
    }

    /** Skips to just past the next restart marker and clears any partial byte. */
    fun alignToRestart() {
        bitCount = 0
        endOfBandRun = 0
        while (offset + 1 < end) {
            if (readUByte(bytes, offset) == MARKER_PREFIX) {
                val next = readUByte(bytes, offset + 1)
                if (next in RESTART_MARKER_LOW..RESTART_MARKER_HIGH) {
                    offset += 2
                    return
                }
            }
            offset++
        }
    }
}
