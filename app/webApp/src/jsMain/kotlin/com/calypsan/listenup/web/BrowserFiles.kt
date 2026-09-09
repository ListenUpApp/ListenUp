package com.calypsan.listenup.web

import com.calypsan.listenup.core.FileSource
import io.ktor.utils.io.ByteReadChannel
import kotlinx.browser.document
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.readByteArray
import org.khronos.webgl.Int8Array
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
import org.w3c.files.File

/**
 * A [RawSink] that keeps everything written to it, for a caller that wants the bytes afterwards.
 *
 * ⛔ **It does not save anything on `close()`, and that is deliberate.** `AdminBackupViewModel`
 * closes the sink in a `finally` — before it knows whether the download succeeded — so a sink that
 * saved on close would hand the reader a truncated archive every time a download failed, named as
 * though it were whole. The page saves on the ViewModel's success event instead, which is the only
 * moment the bytes are known to be a complete backup.
 *
 * ⛔ **The whole archive is held in memory.** `BackupRepository.downloadBackup` streams in chunks
 * precisely so a large image-bearing backup stays memory-safe on a device, and a browser cannot
 * honour that: there is no writable file to stream into. (The File System Access API can, but it is
 * Chromium-only and needs its own permission prompt.) So the shared streaming path is preserved and
 * the buffer is where it lands. A backup far larger than the tab's memory will fail here — noisily,
 * as an allocation error, rather than by writing a bad file.
 */
internal class BufferingSink : RawSink {
    private val buffer = Buffer()

    override fun write(
        source: Buffer,
        byteCount: Long,
    ) {
        buffer.write(source, byteCount)
    }

    override fun flush() = Unit

    /** Nothing to release: the bytes are wanted *after* the caller closes this. */
    override fun close() = Unit

    /** Everything written so far, consuming the buffer. */
    fun bytes(): ByteArray = buffer.readByteArray()
}

/**
 * Hands [bytes] to the browser as a download named [filename].
 *
 * A synthetic anchor rather than `window.open`: an object URL opened in a tab renders the archive
 * as text in some browsers, and only the `download` attribute makes the file arrive with the name
 * the server gave it. The URL is revoked immediately after the click — the browser has already
 * taken its own reference by then, and leaving it alive pins the whole archive in memory for the
 * life of the tab.
 */
internal fun saveToDisk(
    filename: String,
    bytes: ByteArray,
) {
    val blob = Blob(arrayOf(bytes.unsafeCast<Int8Array>()), BlobPropertyBag(type = "application/zip"))
    val url = URL.createObjectURL(blob)
    val anchor = document.createElement("a") as HTMLAnchorElement
    anchor.href = url
    anchor.setAttribute("download", filename)
    // Firefox will not act on a click for an element outside the document.
    document.body?.appendChild(anchor)
    anchor.click()
    anchor.remove()
    URL.revokeObjectURL(url)
}

/**
 * A picked browser file, as the shared [FileSource] the upload path speaks.
 *
 * The bytes are read once by the caller and replayed on each [openChannel], because the interface
 * promises a fresh channel from the beginning every time and a `File`'s own stream is single-use.
 * That means a picked backup is in memory too — the same browser limitation [BufferingSink]
 * documents, from the other direction.
 */
internal class BrowserFileSource(
    private val file: File,
    private val bytes: ByteArray,
) : FileSource {
    override val filename: String get() = file.name

    override val size: Long get() = bytes.size.toLong()

    override fun openChannel(): ByteReadChannel = ByteReadChannel(bytes)
}
