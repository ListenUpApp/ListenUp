package com.calypsan.listenup.core

import io.ktor.utils.io.ByteReadChannel
import platform.Foundation.NSURL

/**
 * iOS implementation of [FileSource] that streams content from a file URL.
 *
 * Uses Foundation's NSInputStream to read file content and converts it to a
 * ByteReadChannel for efficient streaming uploads.
 *
 * @param fileUrl The file URL pointing to the local file
 * @param filename The display filename
 * @param size The file size in bytes, or null if unknown
 */
@Suppress("UnusedPrivateProperty") // fileUrl will be used when iOS streaming is implemented
class AppleFileSource(
    private val fileUrl: NSURL,
    override val filename: String,
    override val size: Long?,
) : FileSource {
    override fun openChannel(): ByteReadChannel {
        // TODO: Implement iOS file streaming when needed
        // For now, throw as iOS backup import is not yet implemented
        throw UnsupportedOperationException(
            "iOS file streaming not yet implemented. Use server-side file selection instead.",
        )
    }
}
