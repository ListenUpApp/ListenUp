package com.calypsan.listenup.server.io

import kotlinx.io.EOFException

/**
 * In-memory [SeekableSource] over a fixed byte array — the test-only
 * counterpart to the production [RandomAccessFile][java.io.RandomAccessFile]
 * source. Lets parser tests drive a synthetic MP4/MP3 byte array (built by the
 * fixtures DSL) through the real parsing seam without touching the filesystem.
 */
internal class ByteArraySeekableSource(
    private val data: ByteArray,
) : SeekableSource {
    private var pos = 0L

    override val length: Long get() = data.size.toLong()

    override fun position(): Long = pos

    override fun seek(offset: Long) {
        pos = offset
    }

    override fun read(
        into: ByteArray,
        count: Int,
    ): Int {
        if (pos >= data.size) return -1
        val n = minOf(count, data.size - pos.toInt())
        data.copyInto(into, 0, pos.toInt(), pos.toInt() + n)
        pos += n
        return n
    }

    override fun readFully(count: Int): ByteArray {
        if (pos + count > data.size) throw EOFException("EOF")
        val out = data.copyOfRange(pos.toInt(), (pos + count).toInt())
        pos += count
        return out
    }

    override fun close() {}
}
