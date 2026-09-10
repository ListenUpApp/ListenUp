package com.calypsan.listenup.server.compression

import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.buffered

/**
 * Streaming raw-DEFLATE decompressor (RFC 1951 §3.2) exposed as a [RawSource].
 *
 * Wraps a raw-DEFLATE byte stream — the headerless format produced by
 * `java.util.zip.Deflater(level, /* nowrap = */ true)` — and yields the original bytes. No zlib or
 * gzip wrapper is expected; feed only the DEFLATE payload.
 *
 * Decoding is incremental: each [readAtMostTo] decodes whole blocks into an internal buffer only as
 * far as the caller's request demands. Memory is the 32 KiB window plus one block's worth of
 * decoded-but-undelivered bytes; for well-formed `java.util.zip` output a block decodes to at most
 * a few hundred KB, but a crafted block's decoded size is not RFC-bounded — which is what
 * [maxOutputBytes] exists for. The ceiling is enforced **per produced byte**, in [emit], not per
 * block: a single block can decode to any length, so a check that ran after it would already have
 * buffered everything it wanted. It is unbounded by default. A truncated or otherwise malformed
 * stream, or one that runs past its budget, raises [MalformedDeflateException].
 */
public class InflateRawSource(
    source: RawSource,
    /**
     * Ceiling on total decompressed output, in bytes. A DEFLATE block's decoded size is not
     * RFC-bounded, so a small compressed stream can declare an arbitrarily large expansion — the
     * budget is what turns that into a decline instead of an allocation. [Long.MAX_VALUE] (the
     * default) preserves the previous unbounded behaviour for callers that have their own bound.
     */
    private val maxOutputBytes: Long = Long.MAX_VALUE,
) : RawSource {
    init {
        require(maxOutputBytes >= 0) { "maxOutputBytes < 0: $maxOutputBytes" }
    }

    private val bufferedSource = source.buffered()
    private val reader = BitReader(bufferedSource)

    // 32 KiB circular history window for back-references (RFC §3.2.3 — distances up to 32768).
    private val window = ByteArray(WINDOW_SIZE)
    private var windowPos = 0

    // Decoded bytes not yet handed to a caller. Grows by at most one block between reads.
    private val pending = Buffer()
    private var finished = false

    // Running count of emitted bytes — used to validate back-reference distances (RFC §3.2.3).
    private var produced = 0L

    // Fixed Huffman tables (RFC §3.2.6) are stream-invariant — build them once and reuse.
    private val fixedLitLen by lazy { HuffmanDecoder(FIXED_LITLEN_LENGTHS) }
    private val fixedDist by lazy { HuffmanDecoder(FIXED_DIST_LENGTHS) }

    override fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        require(byteCount >= 0) { "byteCount < 0: $byteCount" }
        if (byteCount == 0L) return 0L
        while (pending.size < byteCount && !finished) {
            decodeOneBlock()
        }
        if (pending.size == 0L) return -1L
        return pending.readAtMostTo(sink, byteCount)
    }

    override fun close() {
        bufferedSource.close()
    }

    /** Decodes exactly one DEFLATE block into [pending], updating [finished] on the final block. */
    private fun decodeOneBlock() {
        val bfinal = reader.readBits(1)
        when (val btype = reader.readBits(2)) {
            BTYPE_STORED -> {
                decodeStored()
            }

            BTYPE_FIXED -> {
                decodeCompressed(fixedLitLen, fixedDist)
            }

            BTYPE_DYNAMIC -> {
                val (litLen, dist) = readDynamicTables()
                decodeCompressed(litLen, dist)
            }

            else -> {
                throw MalformedDeflateException("reserved block type $btype")
            }
        }
        if (bfinal == 1) finished = true
    }

    /** Stored block (RFC §3.2.4): byte-aligned LEN/NLEN header followed by LEN literal bytes. */
    private fun decodeStored() {
        reader.alignToByte()
        val len = reader.readBits(16)
        val nlen = reader.readBits(16)
        if (nlen != (len.inv() and 0xFFFF)) {
            throw MalformedDeflateException("stored block LEN/NLEN mismatch")
        }
        repeat(len) { emit(reader.readBits(8).toByte()) }
    }

    /** Reads a dynamic block's header (RFC §3.2.7) and returns its literal/length + distance decoders. */
    private fun readDynamicTables(): Pair<HuffmanDecoder, HuffmanDecoder> {
        val hlit = reader.readBits(5) + 257
        val hdist = reader.readBits(5) + 1
        val hclen = reader.readBits(4) + 4

        // Code-length-code lengths, deposited in the RFC's permuted order.
        val codeLengthLengths = IntArray(CODE_LENGTH_ORDER.size)
        for (i in 0 until hclen) {
            codeLengthLengths[CODE_LENGTH_ORDER[i]] = reader.readBits(3)
        }
        val codeLengthDecoder = HuffmanDecoder(codeLengthLengths)

        // Run-length-decode the hlit + hdist literal/length and distance code lengths.
        val all = IntArray(hlit + hdist)
        var i = 0
        while (i < all.size) {
            when (val sym = codeLengthDecoder.decodeSymbol(reader)) {
                in 0..15 -> {
                    all[i++] = sym
                }

                REPEAT_PREVIOUS -> {
                    if (i == 0) throw MalformedDeflateException("repeat with no previous code length")
                    val count = reader.readBits(2) + 3
                    if (i + count > all.size) throw MalformedDeflateException("code-length repeat overflows the table")
                    val prev = all[i - 1]
                    repeat(count) { all[i++] = prev }
                }

                REPEAT_ZERO_SHORT -> {
                    val count = reader.readBits(3) + 3
                    if (i + count > all.size) throw MalformedDeflateException("code-length repeat overflows the table")
                    repeat(count) { all[i++] = 0 }
                }

                REPEAT_ZERO_LONG -> {
                    val count = reader.readBits(7) + 11
                    if (i + count > all.size) throw MalformedDeflateException("code-length repeat overflows the table")
                    repeat(count) { all[i++] = 0 }
                }

                else -> {
                    throw MalformedDeflateException("invalid code-length symbol $sym")
                }
            }
        }

        return HuffmanDecoder(all.copyOfRange(0, hlit)) to
            HuffmanDecoder(all.copyOfRange(hlit, hlit + hdist))
    }

    /** Runs the literal/length symbol loop for a compressed block until end-of-block (symbol 256). */
    private fun decodeCompressed(
        litLen: HuffmanDecoder,
        dist: HuffmanDecoder,
    ) {
        while (true) {
            val sym = litLen.decodeSymbol(reader)
            when {
                sym < END_OF_BLOCK -> emit(sym.toByte())
                sym == END_OF_BLOCK -> return
                sym <= MAX_LENGTH_SYMBOL -> copyBackReference(sym, dist)
                else -> throw MalformedDeflateException("invalid literal/length symbol $sym")
            }
        }
    }

    /** Resolves a length/distance pair (RFC §3.2.5) and copies the run byte-by-byte through the window. */
    private fun copyBackReference(
        lengthSymbol: Int,
        dist: HuffmanDecoder,
    ) {
        val lengthIndex = lengthSymbol - 257
        val length = LENGTH_BASE[lengthIndex] + reader.readBits(LENGTH_EXTRA[lengthIndex])

        val distSymbol = dist.decodeSymbol(reader)
        if (distSymbol > MAX_DISTANCE_SYMBOL) {
            throw MalformedDeflateException("invalid distance symbol $distSymbol")
        }
        val distance = DIST_BASE[distSymbol] + reader.readBits(DIST_EXTRA[distSymbol])

        if (distance > produced) {
            throw MalformedDeflateException("distance $distance exceeds output produced ($produced)")
        }
        // One byte at a time so overlapping runs (e.g. a single byte repeated via distance == 1) work.
        repeat(length) { emit(window[(windowPos - distance) and WINDOW_MASK]) }
    }

    /**
     * Appends [b] to the pending output and records it in the sliding window — unless doing so would
     * take total output past [maxOutputBytes], in which case the stream is declined right here, before
     * the byte lands in [pending]. Per byte, because this is the only place a block's size is bounded.
     */
    private fun emit(b: Byte) {
        if (produced == maxOutputBytes) {
            throw MalformedDeflateException("inflated output exceeded the $maxOutputBytes-byte budget")
        }
        pending.writeByte(b)
        window[windowPos] = b
        windowPos = (windowPos + 1) and WINDOW_MASK
        produced++
    }

    private companion object {
        const val WINDOW_SIZE = 32768
        const val WINDOW_MASK = WINDOW_SIZE - 1

        const val BTYPE_STORED = 0
        const val BTYPE_FIXED = 1
        const val BTYPE_DYNAMIC = 2

        const val END_OF_BLOCK = 256
        const val MAX_LENGTH_SYMBOL = 285
        const val MAX_DISTANCE_SYMBOL = 29

        const val REPEAT_PREVIOUS = 16
        const val REPEAT_ZERO_SHORT = 17
        const val REPEAT_ZERO_LONG = 18
    }
}

/**
 * Wraps this raw-DEFLATE byte stream in an [InflateRawSource] that yields the decompressed bytes,
 * declining past [maxOutputBytes] — see [InflateRawSource.maxOutputBytes]. Unbounded by default.
 */
public fun RawSource.inflated(maxOutputBytes: Long = Long.MAX_VALUE): RawSource = InflateRawSource(this, maxOutputBytes)
