package com.calypsan.listenup.core

import android.content.ContentResolver
import android.net.Uri
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

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
            try {
                contentResolver.openInputStream(uri)
            } catch (e: IOException) {
                throw FileUnreadableException("Could not open $filename", e)
            } catch (e: SecurityException) {
                // The picker's grant lapsed: the file is still there, but it can no longer be read.
                throw FileUnreadableException("Could not open $filename", e)
            } ?: throw FileUnreadableException("Could not open input stream for $filename")

        // Convert InputStream to ByteReadChannel for streaming
        // The ByteReadChannel handles async reads internally - reads will suspend as needed
        // The channel will close the underlying InputStream when done
        return UnreadableAwareInputStream(inputStream, filename).toByteReadChannel()
    }
}

/**
 * Reports a failed read as a [FileUnreadableException] instead of the stream's own `IOException`,
 * which the upload boundary would take for the network being down and retry.
 */
private class UnreadableAwareInputStream(
    delegate: InputStream,
    private val filename: String,
) : FilterInputStream(delegate) {
    override fun read(): Int = unreadable { super.read() }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int = unreadable { super.read(buffer, offset, length) }

    private inline fun <T> unreadable(read: () -> T): T =
        try {
            read()
        } catch (e: IOException) {
            throw FileUnreadableException("Could not read $filename", e)
        }
}
