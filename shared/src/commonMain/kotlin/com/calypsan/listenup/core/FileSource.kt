package com.calypsan.listenup.core

import io.ktor.utils.io.ByteReadChannel

/**
 * Platform-agnostic abstraction for streaming file content.
 *
 * This allows large files to be uploaded without loading them entirely into memory.
 * Each platform implements this differently:
 * - Android: Uses ContentResolver to stream from a content URI
 * - iOS: Uses file URLs with NSInputStream
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
