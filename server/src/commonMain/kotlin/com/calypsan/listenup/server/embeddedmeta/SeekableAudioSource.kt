package com.calypsan.listenup.server.embeddedmeta

import kotlinx.io.files.Path

/**
 * Seekable byte source — the only place in the embeddedmeta package that knows
 * how the platform opens a file for random access. Every parser depends on this
 * interface, never on a platform file primitive directly. If kotlinx-io ships a
 * random-access primitive in a future version, swap [defaultSeekableSource] only.
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
     * Throws if EOF is hit before [count] bytes are available.
     */
    fun readFully(count: Int): ByteArray
}

/** Default factory: opens [path] in read-only mode. */
internal expect fun defaultSeekableSource(path: Path): SeekableAudioSource
