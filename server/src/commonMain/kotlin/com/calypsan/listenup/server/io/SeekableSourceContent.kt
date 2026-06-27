package com.calypsan.listenup.server.io

import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteReadChannel
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.buffered

/**
 * Streaming, byte-range-capable file response backed by the native [SeekableSource] seam — the
 * multiplatform replacement for Ktor's jvm-only `LocalFileContent` / `respondFile`.
 *
 * Reports [contentLength] so the `PartialContent` plugin can answer `206 Partial Content` by calling
 * [readFrom] with a sub-range (verified on linuxX64). Each [readFrom] opens a fresh source —
 * PartialContent may request the body more than once — and streams **only** the requested window in
 * bounded chunks, so a multi-gigabyte audiobook or backup archive never lands in memory.
 */
internal class SeekableSourceContent(
    private val length: Long,
    override val contentType: ContentType,
    private val open: () -> SeekableSource,
) : OutgoingContent.ReadChannelContent() {
    override val contentLength: Long = length

    override fun readFrom(): ByteReadChannel = streamWindow(0, length)

    override fun readFrom(range: LongRange): ByteReadChannel =
        streamWindow(range.first, range.last - range.first + 1)

    private fun streamWindow(
        offset: Long,
        count: Long,
    ): ByteReadChannel {
        val source = open()
        source.seek(offset)
        return ByteReadChannel(BoundedSeekableSource(source, count).buffered())
    }
}

/**
 * A [RawSource] that yields at most [remaining] bytes from an already-positioned [SeekableSource],
 * then signals EOF. Closing it closes the underlying source.
 */
private class BoundedSeekableSource(
    private val source: SeekableSource,
    private var remaining: Long,
) : RawSource {
    override fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        if (remaining <= 0L) return -1L
        val want = minOf(byteCount, remaining, CHUNK).toInt()
        val buffer = ByteArray(want)
        val read = source.read(buffer, want)
        if (read <= 0) {
            remaining = 0L
            return -1L
        }
        sink.write(buffer, 0, read)
        remaining -= read
        return read.toLong()
    }

    override fun close() = source.close()

    private companion object {
        const val CHUNK = 64L * 1024L
    }
}
