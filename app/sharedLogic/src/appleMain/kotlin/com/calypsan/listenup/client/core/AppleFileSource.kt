package com.calypsan.listenup.client.core

import com.calypsan.listenup.core.FileSource
import io.ktor.utils.io.ByteReadChannel
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * A [FileSource] that streams the file at [path] from disk — the construction path Swift export
 * gives iOS for uploads.
 *
 * `fileSourceOf` holds a whole file in memory, which is fine for a backup archive and impossible for
 * an audiobook; this reads as the request body drains it. The caller must keep the file readable for
 * as long as the upload may open it — on iOS, by holding the picker's security-scoped access for the
 * whole session.
 *
 * @param path An absolute filesystem path to a regular file.
 */
fun fileSourceAtPath(path: String): FileSource {
    val file = Path(path)
    return AppleFileSource(
        path = file,
        filename = file.name,
        size = SystemFileSystem.metadataOrNull(file)?.size?.takeIf { it >= 0 },
    )
}

private class AppleFileSource(
    private val path: Path,
    override val filename: String,
    override val size: Long?,
) : FileSource {
    // Each call opens the file afresh, so a retried upload starts from the first byte again.
    override fun openChannel(): ByteReadChannel =
        ByteReadChannel(ClosingAtEndSource(SystemFileSystem.source(path)).buffered())
}

/**
 * Closes [delegate] the moment a read reaches its end.
 *
 * Ktor's `ByteReadChannel(Source)` closes its source only when the channel is cancelled. A channel
 * read to completion — every successful upload — would otherwise keep its file descriptor until the
 * process died: one per track, and a folder of a few hundred tracks exhausts iOS's default limit of
 * 256 partway through the upload. Cancellation still closes it the usual way, through [close].
 */
private class ClosingAtEndSource(
    private val delegate: RawSource,
) : RawSource {
    private var closed = false

    override fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        if (closed) return END_OF_STREAM
        val read = delegate.readAtMostTo(sink, byteCount)
        if (read == END_OF_STREAM) close()
        return read
    }

    override fun close() {
        if (closed) return
        closed = true
        delegate.close()
    }
}

private const val END_OF_STREAM = -1L
