package com.calypsan.listenup.server.imaging

import kotlinx.io.Buffer

/**
 * Bit-level writer for a JPEG entropy-coded segment — the mirror of [JpegBitReader].
 *
 * MSB-first, and **byte-stuffed**: a completed `0xFF` byte is followed by a `0x00` so a decoder
 * scanning for markers cannot mistake image data for one. Getting that wrong produces a file that
 * decodes correctly right up until the first bright pixel run, which is why it lives here rather
 * than at any call site.
 */
internal class JpegBitWriter(
    private val sink: Buffer,
) {
    private var bitBuffer = 0
    private var bitCount = 0

    /** Writes the low [length] bits of [value], most significant first. */
    fun writeBits(
        value: Int,
        length: Int,
    ) {
        for (shift in length - 1 downTo 0) writeBit(value ushr shift and 1)
    }

    private fun writeBit(bit: Int) {
        bitBuffer = bitBuffer shl 1 or bit
        bitCount++
        if (bitCount == BITS_PER_BYTE) {
            val byte = bitBuffer and BYTE_MASK
            sink.writeByte(byte.toByte())
            // Stuffing: an 0xFF in the entropy stream is escaped so it cannot read as a marker.
            if (byte == MARKER_PREFIX) sink.writeByte(0)
            bitBuffer = 0
            bitCount = 0
        }
    }

    /**
     * Pads the final partial byte with 1-bits and flushes it.
     *
     * One-bits, not zeroes: a run of zeroes could complete a valid Huffman code and hand the decoder
     * a phantom coefficient, whereas all-ones is not a prefix of any code in the standard tables.
     */
    fun flush() {
        while (bitCount != 0) writeBit(1)
    }
}

private const val BYTE_MASK = 0xFF

/**
 * A canonical Huffman table turned inside out: symbol → code, for writing.
 *
 * The decoder's [JpegHuffmanTable] indexes by code *length* because it discovers the symbol one bit
 * at a time. An encoder already knows the symbol, so it wants the opposite lookup — same canonical
 * construction, walked once at build time into a flat table.
 */
internal class HuffmanEncoder(
    counts: IntArray,
    values: IntArray,
) {
    private val codes = IntArray(SYMBOL_SPACE)
    private val lengths = IntArray(SYMBOL_SPACE)

    init {
        var code = 0
        var index = 0
        for (length in 1..MAX_CODE_LENGTH) {
            repeat(counts[length - 1]) {
                codes[values[index]] = code
                lengths[values[index]] = length
                code++
                index++
            }
            code = code shl 1
        }
    }

    fun write(
        writer: JpegBitWriter,
        symbol: Int,
    ) {
        require(lengths[symbol] > 0) { "symbol $symbol is not in this Huffman table" }
        writer.writeBits(codes[symbol], lengths[symbol])
    }
}

private const val SYMBOL_SPACE = 256
