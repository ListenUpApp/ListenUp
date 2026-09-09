package com.calypsan.listenup.server.io

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import kotlinx.io.Sink
import kotlinx.io.readByteArray

/** The request body was not well-formed multipart/form-data (missing boundary, truncated, …). */
internal class MalformedMultipartException(
    message: String,
) : RuntimeException(message)

/** The captured file part exceeded the caller's size limit before its boundary was reached. */
internal class MultipartPartTooLargeException(
    val limit: Long,
) : RuntimeException("Multipart file part exceeded the $limit-byte limit.")

private const val DEFAULT_BUFFER_BYTES = 64 * 1024

/**
 * Largest single multipart header line. Real `Content-Disposition` / `Content-Type` lines are well
 * under 1 KiB even with a long UTF-8 filename; 8 KiB matches the conventional HTTP header-line limit
 * and is the point past which a line is not a header.
 */
private const val MAX_HEADER_LINE_BYTES = 8 * 1024

/**
 * Largest whole header block for one part. Bounds the *number* of lines as well as their length —
 * [MultipartBodyReader.readHeaderBlock] loops until a blank line, so a body that never sends one
 * would otherwise loop forever accumulating lines.
 */
private const val MAX_HEADER_BLOCK_BYTES = 16 * 1024

/**
 * Hard ceiling on the scan buffer. The header caps above mean the buffer never *needs* to grow past
 * [DEFAULT_BUFFER_BYTES] — an unterminated line is refused at 8 KiB, and body scanning consumes as it
 * goes — so this sits at four times the default and sixteen times the block cap: a backstop that a
 * future change to the scanner would hit, never a limit a real request meets.
 */
private const val MAX_BUFFER_BYTES = 4 * DEFAULT_BUFFER_BYTES
private val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())

/**
 * Streams the first file part of a `multipart/form-data` body (RFC 7578) on [channel] to the sink from
 * [openSink], up to [formFieldLimit] bytes. Parts before the first file part (and everything after it)
 * are read and discarded. [openSink] is invoked exactly once — lazily, only when a file part is found —
 * so no destination is created for a body that carries no file. Returns true when a file part streamed.
 *
 * Hand-rolled because the Kotlin/Native Ktor CIO server cannot parse multipart through Ktor's transform
 * ([KTOR-7361](https://youtrack.jetbrains.com/issue/KTOR-7361)) — only that transform is broken, so the
 * native actual reads the raw body channel (which works on every engine) and this decodes the wire
 * format directly. It is platform-agnostic Kotlin over a [ByteReadChannel], so the JVM test suite
 * exercises every branch. The body never lands in memory: each part streams through a small fixed
 * buffer, holding back only the last `delimiter.size - 1` bytes per fill so a boundary that straddles
 * two reads is still detected.
 *
 * @param boundary the `boundary` parameter from the request's `Content-Type` header.
 * @throws MalformedMultipartException if the body is truncated, not valid multipart/form-data, or
 *   carries a part header line or block past the header caps.
 * @throws MultipartPartTooLargeException if the file part exceeds [formFieldLimit].
 */
internal suspend fun streamFirstFilePart(
    channel: ByteReadChannel,
    boundary: String,
    formFieldLimit: Long,
    bufferCapacity: Int = DEFAULT_BUFFER_BYTES,
    openSink: () -> Sink,
): Boolean {
    val reader = MultipartBodyReader(channel, boundary, bufferCapacity)
    reader.skipToFirstBoundary()
    while (true) {
        when (reader.readBoundaryTrailer()) {
            BoundaryTrailer.End -> return false
            BoundaryTrailer.More -> Unit
        }
        val isFilePart = reader.readHeaderBlock().any(::isFilePartDisposition)
        if (isFilePart) {
            openSink().use { sink -> reader.streamPartBodyTo(sink, formFieldLimit) }
            return true
        }
        reader.streamPartBodyTo(sink = null, limit = Long.MAX_VALUE)
    }
}

/** A part is a file part when its Content-Disposition carries a `filename` parameter (mirrors Ktor). */
private fun isFilePartDisposition(headerLine: String): Boolean {
    val lower = headerLine.lowercase()
    return lower.startsWith("content-disposition:") &&
        (lower.contains("filename=") || lower.contains("filename*="))
}

private enum class BoundaryTrailer { More, End }

/** One CRLF-terminated header line, and the bytes it occupied on the wire including the terminator. */
private class HeaderLine(
    val text: String,
    val byteCount: Int,
)

/**
 * Buffered, holdback-aware reader over a [ByteReadChannel] for one multipart body. All positions are
 * indices into [buf]; [fill] compacts and refills from the channel, growing the buffer only if a
 * single token cannot fit and never past [MAX_BUFFER_BYTES] — a boundary too long to fit under that
 * ceiling is refused at construction rather than left to deadlock the scan.
 */
private class MultipartBodyReader(
    private val channel: ByteReadChannel,
    boundary: String,
    capacity: Int,
) {
    // Inter-part delimiter is CRLF + "--boundary"; the very first boundary has no leading CRLF.
    private val dashBoundary = "--$boundary".encodeToByteArray()
    private val delimiter = CRLF + dashBoundary

    init {
        // The buffer must always hold two delimiters plus a CRLF for the holdback scan to make
        // progress. Now that it cannot grow without limit, a delimiter that would not fit under the
        // ceiling could never be matched — the reader would stall — so it is refused here instead.
        require(delimiter.size * 2 + 2 <= MAX_BUFFER_BYTES) { "Multipart boundary is too long to scan for." }
    }

    private var buf = ByteArray(maxOf(capacity, delimiter.size * 2 + 2))
    private var pos = 0
    private var limit = 0
    private var eof = false

    /** Discards the preamble and consumes the opening `--boundary`. */
    suspend fun skipToFirstBoundary() {
        while (true) {
            val idx = indexOf(dashBoundary, pos)
            if (idx >= 0) {
                pos = idx + dashBoundary.size
                return
            }
            // Keep the last (dashBoundary.size - 1) bytes — they may begin the boundary in the next fill.
            pos = safeEnd(dashBoundary.size)
            if (!fill()) throw MalformedMultipartException("No multipart boundary found in body.")
        }
    }

    /** Reads the two bytes after a boundary: `--` ends the multipart, CRLF introduces the next part. */
    suspend fun readBoundaryTrailer(): BoundaryTrailer {
        ensure(2)
        if (buf[pos] == '-'.code.toByte() && buf[pos + 1] == '-'.code.toByte()) {
            pos += 2
            return BoundaryTrailer.End
        }
        // Tolerate optional transport padding (spaces/tabs) before the CRLF.
        while (buf[pos] == ' '.code.toByte() || buf[pos] == '\t'.code.toByte()) {
            pos++
            ensure(1)
        }
        ensure(2)
        if (buf[pos] != '\r'.code.toByte() || buf[pos + 1] != '\n'.code.toByte()) {
            throw MalformedMultipartException("Malformed boundary terminator.")
        }
        pos += 2
        return BoundaryTrailer.More
    }

    /**
     * Reads part headers up to (and consuming) the blank line that ends the header block. The block
     * as a whole is capped at [MAX_HEADER_BLOCK_BYTES]: a part that never sends the blank line cannot
     * keep the reader collecting lines.
     */
    suspend fun readHeaderBlock(): List<String> {
        val headers = mutableListOf<String>()
        var blockBytes = 0
        while (true) {
            val line = readLine()
            blockBytes += line.byteCount
            if (blockBytes > MAX_HEADER_BLOCK_BYTES) {
                throw MalformedMultipartException("Multipart header block exceeded $MAX_HEADER_BLOCK_BYTES bytes.")
            }
            if (line.text.isEmpty()) return headers
            headers += line.text
        }
    }

    /**
     * Streams the current part's body to [sink] (or discards it when null) until the next delimiter,
     * which it consumes. Enforces [limit] on bytes emitted to a non-null sink.
     */
    suspend fun streamPartBodyTo(
        sink: Sink?,
        limit: Long,
    ) {
        var emitted = 0L
        while (true) {
            val idx = indexOf(delimiter, pos)
            if (idx >= 0) {
                emitted = emit(sink, pos, idx - pos, emitted, limit)
                pos = idx + delimiter.size
                return
            }
            val end = safeEnd(delimiter.size)
            if (end > pos) {
                emitted = emit(sink, pos, end - pos, emitted, limit)
                pos = end
            }
            if (!fill()) throw MalformedMultipartException("Multipart body ended before its boundary.")
        }
    }

    private fun emit(
        sink: Sink?,
        offset: Int,
        count: Int,
        emitted: Long,
        limit: Long,
    ): Long {
        if (sink == null || count == 0) return emitted
        val total = emitted + count
        if (total > limit) throw MultipartPartTooLargeException(limit)
        sink.write(buf, offset, offset + count)
        return total
    }

    private suspend fun readLine(): HeaderLine {
        while (true) {
            val idx = indexOf(CRLF, pos)
            if (idx >= 0) {
                val line = HeaderLine(buf.decodeToString(pos, idx), byteCount = idx - pos + CRLF.size)
                pos = idx + CRLF.size
                return line
            }
            // Everything from pos to limit is one still-unterminated line. Refuse it before fetching
            // more — this is the check that keeps [fill] from ever needing to grow for a header.
            if (limit - pos > MAX_HEADER_LINE_BYTES) {
                throw MalformedMultipartException("Multipart header line exceeded $MAX_HEADER_LINE_BYTES bytes.")
            }
            if (!fill()) throw MalformedMultipartException("Multipart header line was not terminated.")
        }
    }

    /** Index of [pattern] in `buf[from until limit]`, or -1. Patterns here are short (a boundary). */
    private fun indexOf(
        pattern: ByteArray,
        from: Int,
    ): Int {
        val last = limit - pattern.size
        var i = maxOf(from, 0)
        while (i <= last) {
            var j = 0
            while (j < pattern.size && buf[i + j] == pattern[j]) j++
            if (j == pattern.size) return i
            i++
        }
        return -1
    }

    /** The largest index up to which bytes are safe to consume without splitting a [keep]-byte token. */
    private fun safeEnd(keep: Int): Int = if (limit - pos > keep - 1) limit - (keep - 1) else pos

    /** Ensures at least [n] bytes are buffered, or throws if the channel ends first. */
    private suspend fun ensure(n: Int) {
        while (limit - pos < n) {
            if (!fill()) throw MalformedMultipartException("Multipart body ended unexpectedly.")
        }
    }

    /**
     * Compacts consumed bytes, then reads one more chunk. Returns false at end of channel. Grows the
     * buffer only when it is entirely unconsumed, and never past [MAX_BUFFER_BYTES]: reaching that
     * ceiling means the scanner is holding bytes it should have consumed or refused, so it fails
     * loudly rather than allocating on.
     */
    private suspend fun fill(): Boolean {
        if (pos > 0) {
            buf.copyInto(buf, 0, pos, limit)
            limit -= pos
            pos = 0
        }
        if (limit == buf.size) {
            if (buf.size >= MAX_BUFFER_BYTES) {
                throw MalformedMultipartException("Multipart buffer exceeded $MAX_BUFFER_BYTES bytes.")
            }
            buf = buf.copyOf(minOf(buf.size * 2, MAX_BUFFER_BYTES))
        }
        if (eof) return false
        val chunk = channel.readRemaining((buf.size - limit).toLong()).readByteArray()
        if (chunk.isEmpty()) {
            eof = true
            return false
        }
        chunk.copyInto(buf, limit)
        limit += chunk.size
        return true
    }
}
