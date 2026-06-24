package com.calypsan.listenup.server.embeddedmeta

import java.io.RandomAccessFile
import kotlinx.io.files.Path

internal actual fun defaultSeekableSource(path: Path): SeekableAudioSource =
    RandomAccessFileSource(RandomAccessFile(path.toString(), "r"))

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
