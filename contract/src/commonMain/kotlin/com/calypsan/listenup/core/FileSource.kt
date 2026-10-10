package com.calypsan.listenup.core

import io.ktor.utils.io.ByteReadChannel

/**
 * Platform-agnostic abstraction for streaming file content.
 *
 * This allows large files to be uploaded without loading them entirely into memory.
 * Each platform implements this differently:
 * - Android: Uses ContentResolver to stream from a content URI
 * - iOS: Uses file URLs with NSInputStream
 *
 * An implementation whose underlying file cannot be read (deleted, moved, unplugged, a placeholder
 * that never downloaded) must fail its channel with [FileUnreadableException], not an `IOException`:
 * the upload boundary treats an `IOException` as the network being down.
 */
interface FileSource {
    /** The filename for display and upload purposes. */
    val filename: String

    /** The file size in bytes, or null if unknown. */
    val size: Long?

    /**
     * Open a new channel for reading the file content.
     *
     * Each call should return a fresh channel starting from the beginning.
     * The caller is responsible for consuming the channel.
     *
     * Note: This is intentionally non-suspend because [ByteReadChannel] handles
     * async operations internally. The channel reads will suspend as needed.
     */
    fun openChannel(): ByteReadChannel
}

/**
 * A [FileSource] could not read the file behind it.
 *
 * Thrown from the source's channel when the underlying file cannot be read, so the upload boundary
 * can tell a local read fault from a network fault: an upload request fails with a
 * `ClosedByteChannelException` whose cause chain ends here. It deliberately does NOT extend
 * `IOException` — every `IOException` is read as the network being unavailable.
 */
class FileUnreadableException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
