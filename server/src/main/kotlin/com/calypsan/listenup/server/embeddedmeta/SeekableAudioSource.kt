package com.calypsan.listenup.server.embeddedmeta

import kotlinx.io.files.Path
import java.io.RandomAccessFile

/**
 * Seekable byte source — the only place in the embeddedmeta package that knows
 * about JVM file IO. Every parser depends on this interface, never on
 * [RandomAccessFile] directly. If kotlinx-io ships a random-access primitive
 * in a future version, swap [defaultSeekableSource] in this file only.
 */
internal interface SeekableAudioSource : AutoCloseable {
    /** Total length of the underlying file in bytes. */
    val length: Long

    /** Current read position. */
    fun position(): Long

    /** Move the read position to [offset] bytes from the start of the file. */
    fun seek(offset: Long)

    /**
     * Read up to [count] bytes into [into], returning the number actually read.
     * Returns -1 on EOF.
     */
    fun read(
        into: ByteArray,
        count: Int = into.size,
    ): Int

    /**
     * Read exactly [count] bytes and return them as a new array.
     * Throws [java.io.IOException] if EOF is hit before [count] bytes are available.
     */
    fun readFully(count: Int): ByteArray
}

/** Default factory: opens a [RandomAccessFile] in read-only mode. */
internal fun defaultSeekableSource(path: Path): SeekableAudioSource = RandomAccessFileSource(RandomAccessFile(path.toString(), "r"))

private class RandomAccessFileSource(
    private val raf: RandomAccessFile,
) : SeekableAudioSource {
    override val length: Long get() = raf.length()

    override fun position(): Long = raf.filePointer

    override fun seek(offset: Long) {
        raf.seek(offset)
    }

    override fun read(
        into: ByteArray,
        count: Int,
    ): Int = raf.read(into, 0, count)

    override fun readFully(count: Int): ByteArray {
        val buf = ByteArray(count)
        raf.readFully(buf)
        return buf
    }

    override fun close() = raf.close()
}
