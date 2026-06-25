package com.calypsan.listenup.server.compression

import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.buffered
import kotlinx.io.readByteArray

/** Default deflate effort level (1..9 trade speed for ratio; 0 = stored/no compression). */
public const val DEFAULT_LEVEL: Int = 6

/**
 * Compresses bytes written to it into a raw DEFLATE (RFC 1951) stream on [sink]. Buffers all written
 * bytes in memory, then on [close] emits them as DEFLATE blocks (BFINAL=1 on the last) and closes the
 * underlying sink. The whole input is held in memory until [close]; a future task may emit blocks
 * incrementally. [level] 0 = stored (no compression); 1..9 = LZ77 + dynamic-Huffman compression with
 * a deeper match search at higher levels, falling back to a stored block whenever that would be
 * smaller (so incompressible input never pathologically expands). Output is standard raw DEFLATE that
 * any RFC 1951 inflater (incl. java.util.zip) reads.
 */
public class DeflateRawSink(
    sink: RawSink,
    private val level: Int = DEFAULT_LEVEL,
) : RawSink {
    init {
        require(level in 0..9) { "level must be 0..9, was $level" }
    }

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
        try {
            val data = input.readByteArray()
            if (level == 0) emitStored(data) else compress(data)
        } finally {
            out.close() // flushes, then releases the underlying sink (RealSink.close)
        }
    }

    /**
     * Compresses [data] via LZ77 + dynamic Huffman, emitting a single final dynamic block — unless a
     * stored block would be smaller, in which case it falls back to [emitStored]. The plan computes
     * the dynamic block's exact bit cost up front so the choice never expands incompressible input.
     */
    private fun compress(data: ByteArray) {
        val tokens = lz77(data, level)
        val plan = planDynamicBlock(tokens)
        if (plan.totalBits < storedSizeBits(data.size)) {
            emitDynamicBlock(writer, tokens, plan, isFinal = true)
        } else {
            emitStored(data)
        }
    }

    /** Size in bits of [dataSize] bytes encoded as stored blocks (5 bytes of framing per ≤64 KiB block). */
    private fun storedSizeBits(dataSize: Int): Long {
        val blocks = maxOf(1L, (dataSize.toLong() + 65534) / 65535)
        return (dataSize.toLong() + 5L * blocks) * 8L
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
        // Every block ends byte-aligned (writeBytes enforces alignment), so no final padding is needed.
        writer.flush()
    }
}

/** Wraps this [RawSink] in a [DeflateRawSink] that compresses at [level] before writing. */
public fun RawSink.deflated(level: Int = DEFAULT_LEVEL): RawSink = DeflateRawSink(this, level)
