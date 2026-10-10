package com.calypsan.listenup.web

import com.calypsan.listenup.core.FileSource
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.coroutines.CoroutineScope
import kotlinx.io.IOException
import kotlinx.browser.document
import kotlinx.browser.window
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
 * A picked browser file, as the shared [FileSource] the upload path speaks, streamed from the file.
 *
 * [openChannel] returns at once with a channel a writer coroutine on [scope] fills one
 * [sliceBytes]-sized `Blob.slice` at a time, so a file of any size is only ever a slice and the
 * channel's own buffer in memory. Each call starts a fresh pump from offset zero, which is what the
 * interface promises and what a retried upload relies on; [size] is the file's own, known without
 * reading it.
 *
 * A slice the browser cannot read (the file was moved, deleted, or its disk went away mid-upload)
 * closes the channel with an [IOException], so the transfer fails and says so, rather than ending
 * early as though the file were whole.
 *
 * [scope] must be the upload's own: cancelling it stops the pump, so an abandoned upload stops
 * reading the disk.
 */
internal class BrowserFileSource(
    private val file: File,
    private val scope: CoroutineScope,
    private val sliceBytes: Int = UPLOAD_SLICE_BYTES,
    private val readSlice: suspend (File, Double, Double) -> ByteArray? = ::readFileSlice,
) : FileSource {
    override val filename: String get() = file.name

    override val size: Long get() = file.size.toLong()

    override fun openChannel(): ByteReadChannel =
        scope
            .writer {
                val end = file.size.toDouble()
                var offset = 0.0
                while (offset < end) {
                    val next = minOf(offset + sliceBytes, end)
                    val bytes = readSlice(file, offset, next) ?: throw IOException("Could not read ${file.name}")
                    channel.writeFully(bytes)
                    channel.flush()
                    offset = next
                }
            }.channel
}

/**
 * A picked browser file whose bytes the caller already holds, replayed on each [openChannel].
 *
 * For the single-archive flows (a backup, an import) that read their one file whole before handing
 * it over, the same browser limitation [BufferingSink] documents from the other direction. A library
 * upload does not use this: it streams through [BrowserFileSource].
 */
internal class BufferedFileSource(
    private val file: File,
    private val bytes: ByteArray,
) : FileSource {
    override val filename: String get() = file.name

    override val size: Long get() = bytes.size.toLong()

    override fun openChannel(): ByteReadChannel = ByteReadChannel(bytes)
}

/**
 * How much of a file the upload reads at a time: 1 MiB, large enough that the per-slice round
 * trip through `FileReader` is noise against the network, small enough that a tab holds only a
 * sliver of a multi-GiB audiobook.
 */
internal const val UPLOAD_SLICE_BYTES: Int = 1024 * 1024

/**
 * Puts [text] on the clipboard, reporting whether it actually got there.
 *
 * ⛔ `navigator.clipboard` exists only in a secure context, and a self-hosted server reached over
 * plain http on a LAN is exactly the case where it is missing — which is most of this app's
 * deployments. It is also asynchronous, so the answer arrives after this returns. Every call site
 * must therefore keep the text visible and selectable regardless, and treat the callback's `false`
 * as "tell them to copy it themselves", never as an error.
 */
internal fun copyToClipboard(
    text: String,
    onResult: (Boolean) -> Unit,
) {
    val clipboard = window.navigator.asDynamic().clipboard
    if (clipboard == null || clipboard == undefined) {
        onResult(false)
        return
    }
    clipboard
        .writeText(text)
        .then({ onResult(true) }, { onResult(false) })
}
