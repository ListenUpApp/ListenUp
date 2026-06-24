@file:OptIn(ExperimentalForeignApi::class)

package com.calypsan.listenup.server.embeddedmeta

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.io.EOFException
import kotlinx.io.IOException
import kotlinx.io.files.Path
import platform.posix.O_RDONLY
import platform.posix.SEEK_CUR
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.close
import platform.posix.lseek
import platform.posix.open
import platform.posix.read

internal actual fun defaultSeekableSource(path: Path): SeekableAudioSource = PosixFileSource(path)

/**
 * Posix-backed seekable source. The cursor is the OS file position — [seek] is an
 * `lseek(SEEK_SET)`, [read] advances it — which matches the JVM `RandomAccessFile`
 * actual exactly, so the mp3/mp4 parsers behave identically across platforms.
 */
private class PosixFileSource(
    path: Path,
) : SeekableAudioSource {
    private val fd: Int =
        open(path.toString(), O_RDONLY).also { if (it < 0) throw IOException("open failed: $path") }

    override val length: Long =
        run {
            val end = lseek(fd, 0L, SEEK_END)
            lseek(fd, 0L, SEEK_SET) // measure once, reset the cursor to the start
            end
        }

    override fun position(): Long = lseek(fd, 0L, SEEK_CUR)

    override fun seek(offset: Long) {
        lseek(fd, offset, SEEK_SET)
    }

    override fun read(
        into: ByteArray,
        count: Int,
    ): Int =
        into.usePinned { pinned ->
            val n = read(fd, pinned.addressOf(0), count.convert()).toInt()
            if (n <= 0) -1 else n
        }

    override fun readFully(count: Int): ByteArray {
        val buf = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val n =
                buf.usePinned { pinned ->
                    read(fd, pinned.addressOf(offset), (count - offset).convert()).toInt()
                }
            if (n <= 0) throw EOFException("EOF after $offset of $count bytes")
            offset += n
        }
        return buf
    }

    override fun close() {
        close(fd)
    }
}
