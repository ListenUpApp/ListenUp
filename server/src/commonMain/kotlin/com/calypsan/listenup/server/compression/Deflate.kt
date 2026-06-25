package com.calypsan.listenup.server.compression

import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.buffered
import kotlinx.io.readByteArray

/** Default deflate effort level (1..9 trade speed for ratio; 0 = stored/no compression). */
public const val DEFAULT_LEVEL: Int = 6

/**
 * Compresses bytes written to it into a raw DEFLATE (RFC 1951) stream on [sink]. Streaming; [close]
 * emits the final block (BFINAL=1) and flushes. [level] 0 = stored (no compression); 1..9 = effort
 * (added in a later task — for now non-zero levels also use the stored path). Output is standard raw
 * DEFLATE that any RFC 1951 inflater (incl. java.util.zip) reads.
 */
public class DeflateRawSink(
    sink: RawSink,
    private val level: Int = DEFAULT_LEVEL,
) : RawSink {
    private val out = sink.buffered()
    private val writer = BitWriter(out)
    private val input = Buffer()
    private var closed = false

    override fun write(
        source: Buffer,
        byteCount: Long,
    ) {
        check(!closed) { "sink is closed" }
        input.write(source, byteCount)
    }

    override fun flush() = out.flush()

    override fun close() {
        if (closed) return
        closed = true
        emitStored(input.readByteArray()) // TODO Task 6: route level>=1 to a real compressor
        out.flush()
    }

    /**
     * Emits [data] as one or more stored blocks (BTYPE=00), the last marked BFINAL=1. Each block holds
     * ≤ 65535 bytes: header BFINAL(1)+BTYPE=00(2), byte-align, LEN(16 LE), NLEN = ~LEN (16 LE), raw
     * bytes. Empty input emits a single empty final stored block.
     */
    private fun emitStored(data: ByteArray) {
        val maxBlock = 65535
        var offset = 0
        do {
            val len = minOf(maxBlock, data.size - offset)
            val isFinal = offset + len >= data.size
            writer.writeBits(if (isFinal) 1 else 0, 1) // BFINAL
            writer.writeBits(0, 2) // BTYPE = 00 (stored)
            writer.alignToByte()
            writer.writeBits(len, 16) // LEN (little-endian via LSB-first writeBits)
            writer.writeBits(len.inv() and 0xFFFF, 16) // NLEN = one's complement of LEN
            if (len > 0) writer.writeBytes(data.copyOfRange(offset, offset + len))
            offset += len
        } while (offset < data.size)
        writer.alignToByte()
        writer.flush()
    }
}

/** Wraps this [RawSink] in a [DeflateRawSink] that compresses at [level] before writing. */
public fun RawSink.deflated(level: Int = DEFAULT_LEVEL): RawSink = DeflateRawSink(this, level)
