package com.calypsan.listenup.core

import android.content.ContentResolver
import android.net.Uri
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toByteReadChannel

/**
 * Android implementation of [FileSource] that streams content from a content URI.
 *
 * Uses Android's ContentResolver to open an InputStream and converts it to a
 * ByteReadChannel for efficient streaming uploads without loading the entire
 * file into memory.
 *
 * @param contentResolver The ContentResolver to use for opening the URI
 * @param uri The content URI pointing to the file
 * @param filename The display filename
 * @param size The file size in bytes, or null if unknown
 */
class AndroidFileSource(
    private val contentResolver: ContentResolver,
    private val uri: Uri,
    override val filename: String,
    override val size: Long?,
) : FileSource {
    override fun openChannel(): ByteReadChannel {
        val inputStream =
            contentResolver.openInputStream(uri)
                ?: error("Could not open input stream for URI: $uri")

        // Convert InputStream to ByteReadChannel for streaming
        // The ByteReadChannel handles async reads internally - reads will suspend as needed
        // The channel will close the underlying InputStream when done
        return inputStream.toByteReadChannel()
    }
}
