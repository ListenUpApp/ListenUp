package com.calypsan.listenup.server.compression

import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readByteArray

/**
 * Reads LSB-first bit fields from a byte [Source] (RFC 1951 §3.1.1 packing). Maintains a small bit
 * accumulator; raw value fields are returned LSB-first. Use [alignToByte] to skip to a byte boundary.
 */
internal class BitReader(
    private val source: Source,
) {
    private var bitBuffer = 0L
    private var bitCount = 0

    /** Reads [count] bits (0..32) LSB-first and returns them as an Int. Refills from [source] as needed. */
    fun readBits(count: Int): Int {
        require(count in 0..32) { "count out of range: $count" }
        while (bitCount < count) {
            if (source.exhausted()) throw MalformedDeflateException("unexpected end of stream")
            bitBuffer = bitBuffer or ((source.readByte().toLong() and 0xFF) shl bitCount)
            bitCount += 8
        }
        val result = (bitBuffer and ((1L shl count) - 1)).toInt()
        bitBuffer = bitBuffer ushr count
        bitCount -= count
        return result
    }

    /** Discards buffered bits up to the next byte boundary (used before a stored block's LEN/NLEN). */
    fun alignToByte() {
        val drop = bitCount and 7
        bitBuffer = bitBuffer ushr drop
        bitCount -= drop
    }

    /** Reads [n] whole bytes directly (must be byte-aligned — call [alignToByte] first). */
    fun readBytes(n: Int): ByteArray {
        require(bitCount % 8 == 0) { "readBytes requires byte alignment" }
        val out = ByteArray(n)
        var i = 0
        while (bitCount >= 8 && i < n) {
            out[i++] = (bitBuffer and 0xFF).toByte()
            bitBuffer = bitBuffer ushr 8
            bitCount -= 8
        }
        if (i < n) {
            // kotlinx-io readByteArray throws EOFException on a short source; request() returns false
            // instead, so we surface the codec's own MalformedDeflateException on a truncated stream.
            if (!source.request((n - i).toLong())) throw MalformedDeflateException("unexpected end of stream")
            source.readByteArray(n - i).copyInto(out, i)
        }
        return out
    }
}

/**
 * Writes LSB-first bit fields to a byte [Sink] (RFC 1951 §3.1.1 packing). Whole bytes are emitted to
 * the sink immediately; only 0–7 partial bits are ever buffered. [alignToByte] pads the current byte
 * with zero bits (emitting it); [flush] then forwards to the sink.
 */
internal class BitWriter(
    private val sink: Sink,
) {
    private var bitBuffer = 0L
    private var bitCount = 0

    /** Writes the low [count] bits of [value] LSB-first. */
    fun writeBits(
        value: Int,
        count: Int,
    ) {
        bitBuffer = bitBuffer or ((value.toLong() and ((1L shl count) - 1)) shl bitCount)
        bitCount += count
        while (bitCount >= 8) {
            sink.writeByte((bitBuffer and 0xFF).toByte())
            bitBuffer = bitBuffer ushr 8
            bitCount -= 8
        }
    }

    /** Pads with zero bits to the next byte boundary. */
    fun alignToByte() {
        if (bitCount % 8 != 0) writeBits(0, 8 - (bitCount % 8))
    }

    /** Writes [bytes] directly (must be byte-aligned — call [alignToByte] first). */
    fun writeBytes(bytes: ByteArray) {
        require(bitCount % 8 == 0) { "writeBytes requires byte alignment" }
        flush()
        sink.write(bytes)
    }

    /**
     * Forwards to the underlying sink's flush. Does **not** pad partial bits — call [alignToByte]
     * before the final flush to emit the last partial byte.
     */
    fun flush() {
        sink.flush()
    }
}
