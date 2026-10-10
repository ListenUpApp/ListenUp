package com.calypsan.listenup.client.core

import com.calypsan.listenup.core.FileSource
import io.ktor.utils.io.ByteReadChannel
import org.w3c.files.File

/**
 * A picked browser [File], as the [FileSource] the shared upload path speaks — without reading it.
 *
 * The bytes stay on disk. The browser's upload transport (`XhrUploadTransport`) hands the [file]
 * itself to a `FormData`, and the browser streams it from disk at any size. That is the whole
 * point: reading a picked file into memory first is what capped a browser upload at what a tab
 * could hold, and Ktor's JS engine copies a request body again on top of that — a real audiobook
 * died there with `RangeError: Invalid array length`.
 *
 * ⛔ [openChannel] refuses rather than reads. A `File` can only be read asynchronously, and the
 * interface's channel is synchronous, so the only honest channel would be a buffered copy — the
 * exact thing this type exists to avoid. Nothing reaches it: the browser binds the upload API that
 * sends this source through XMLHttpRequest, and no other path is handed one.
 */
class BlobFileSource(
    /** The picked file, handed to the browser's own transport as-is. */
    val file: File,
) : FileSource {
    override val filename: String get() = file.name

    override val size: Long get() = file.size.toLong()

    override fun openChannel(): ByteReadChannel =
        throw UnsupportedOperationException(
            "A picked browser file is sent by XMLHttpRequest from disk; it is never read into a channel.",
        )
}
