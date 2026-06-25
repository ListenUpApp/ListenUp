package com.calypsan.listenup.server.compression

import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.buffered
import kotlinx.io.readByteArray

/** Default deflate effort level (1..9 trade speed for ratio; 0 = stored/no compression). */
public const val DEFAULT_LEVEL: Int = 6

/**
 * Compresses bytes written to it into a raw DEFLATE (RFC 1951) stream on [sink].
 *
 * Memory is **bounded**: input is processed in fixed-size windows ([BLOCK_INPUT_SIZE]), each emitted
 * as its own DEFLATE block, so the working set is roughly one window plus a 32 KiB back-reference
 * history (~constant) regardless of total input size. Backups of any size — MB to GB — compress
 * without buffering the whole stream. As each window fills it is emitted from [write]; the remainder
 * is flushed on [close], which then closes the underlying sink.
 *
 * The output is a sequence of DEFLATE blocks. Exactly one block — the last one emitted at [close] —
 * carries BFINAL=1; every earlier block carries BFINAL=0 and leaves its trailing partial bits for the
 * next block to continue (blocks are bit-packed, not byte-aligned). Back-references in a block may
 * reach up to 32768 bytes into earlier blocks, so cross-window matches are exploited. Empty input
 * still emits one empty final block so inflaters terminate.
 *
 * [level] 0 = stored (no compression); 1..9 = LZ77 + dynamic-Huffman compression with a deeper match
 * search at higher levels, falling back to a stored block per window whenever that would be smaller
 * (so incompressible input never pathologically expands). Output is standard raw DEFLATE that any RFC
 * 1951 inflater (incl. java.util.zip) reads.
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
    private val pending = Buffer()
    private var history = EMPTY
    private var closed = false

    override fun write(
        source: Buffer,
        byteCount: Long,
    ) {
        check(!closed) { "sink is closed" }
        pending.write(source, byteCount)
        while (pending.size >= BLOCK_INPUT_SIZE) {
            emitBlock(pending.readByteArray(BLOCK_INPUT_SIZE), isFinal = false)
        }
    }

    override fun flush() = out.flush()

    override fun close() {
        if (closed) return
        closed = true
        try {
            // Emit whatever is left as the final block — possibly empty, which still terminates the stream.
            emitBlock(pending.readByteArray(), isFinal = true)
        } finally {
            out.close() // flushes, then releases the underlying sink (RealSink.close)
        }
    }

    /**
     * Emits one DEFLATE block for [chunk]. The matcher sees [history] ++ [chunk] so back-references in
     * [chunk] can reach into the previous window (distance ≤ 32768); only [chunk] is encoded. A dynamic
     * block is chosen unless a stored block would be smaller. [isFinal] sets BFINAL on this block, which
     * is true only for the last block. Afterwards [history] becomes the last ≤32768 bytes emitted.
     */
    private fun emitBlock(
        chunk: ByteArray,
        isFinal: Boolean,
    ) {
        if (level == 0) {
            emitStored(chunk, isFinal)
            return
        }
        val combined = if (history.isEmpty()) chunk else history + chunk
        val tokens = lz77(combined, emitFrom = history.size, level = level)
        val plan = planDynamicBlock(tokens)
        if (plan.totalBits < storedSizeBits(chunk.size)) {
            emitDynamicBlock(writer, tokens, plan, isFinal)
        } else {
            emitStored(chunk, isFinal)
        }
        history = combined.lastWindow()
    }

    /** Size in bits of [dataSize] bytes encoded as stored blocks (5 bytes of framing per ≤64 KiB block). */
    private fun storedSizeBits(dataSize: Int): Long {
        val blocks = maxOf(1L, (dataSize.toLong() + 65534) / 65535)
        return (dataSize.toLong() + 5L * blocks) * 8L
    }

    /**
     * Emits [data] as one or more stored sub-blocks (BTYPE=00). Each holds ≤ 65535 bytes: header
     * BFINAL(1)+BTYPE=00(2), byte-align, LEN(16 LE), NLEN = ~LEN (16 LE), raw bytes. BFINAL=1 is set
     * only on the final sub-block of a final block ([isFinal]); all others are BFINAL=0. Empty [data]
     * emits a single empty stored sub-block (BFINAL=[isFinal]).
     */
    private fun emitStored(
        data: ByteArray,
        isFinal: Boolean,
    ) {
        val maxBlock = 65535
        var offset = 0
        do {
            val len = minOf(maxBlock, data.size - offset)
            val lastSubBlock = offset + len >= data.size
            writer.writeBits(if (lastSubBlock && isFinal) 1 else 0, 1) // BFINAL
            writer.writeBits(0, 2) // BTYPE = 00 (stored)
            writer.alignToByte()
            writer.writeBits(len, 16) // LEN (little-endian via LSB-first writeBits)
            writer.writeBits(len.inv() and 0xFFFF, 16) // NLEN = one's complement of LEN
            if (len > 0) writer.writeBytes(data.copyOfRange(offset, offset + len))
            offset += len
        } while (offset < data.size)
        // Every stored sub-block ends byte-aligned (writeBytes enforces alignment); flush the bytes downstream.
        writer.flush()
    }
}

/** Bytes accumulated before a window is cut and emitted as one block — bounds the working-set size. */
private const val BLOCK_INPUT_SIZE = 1 shl 20 // 1 MiB

/** Maximum back-reference distance retained as history between blocks (DEFLATE's window, RFC §3.2.5). */
private const val MAX_HISTORY = 32768

private val EMPTY = ByteArray(0)

/** The last [MAX_HISTORY] bytes of this array (the whole array when shorter) — the next block's history. */
private fun ByteArray.lastWindow(): ByteArray = if (size <= MAX_HISTORY) this else copyOfRange(size - MAX_HISTORY, size)

/** Wraps this [RawSink] in a [DeflateRawSink] that compresses at [level] before writing. */
public fun RawSink.deflated(level: Int = DEFAULT_LEVEL): RawSink = DeflateRawSink(this, level)
