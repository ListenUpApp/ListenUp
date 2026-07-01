package com.calypsan.listenup.server.io

import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveMultipart
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/** Bounded read size for streaming an upload to disk — never holds the whole body in memory. */
private const val UPLOAD_CHUNK_BYTES: Long = 64L * 1024

internal actual suspend fun ApplicationCall.streamFirstFilePartTo(
    dest: Path,
    formFieldLimit: Long,
): Boolean {
    var received = false
    receiveMultipart(formFieldLimit = formFieldLimit).forEachPart { part ->
        if (part is PartData.FileItem && !received) {
            received = true
            part.provider().writeTo(dest)
        }
        part.release()
    }
    return received
}

internal actual suspend fun ApplicationCall.receiveFirstFilePartBytes(formFieldLimit: Long): ByteArray? {
    var bytes: ByteArray? = null
    // Ktor's default formFieldLimit (50 MiB) governs the transform; our own cap is enforced by
    // readCappedBytes so it fires deterministically as MultipartPartTooLargeException regardless of
    // how the engine treats file-part limits (matching the native decoder's contract).
    receiveMultipart().forEachPart { part ->
        if (part is PartData.FileItem && bytes == null) {
            bytes = part.provider().readCappedBytes(formFieldLimit)
        }
        part.release()
    }
    return bytes
}

/** Reads this channel fully into memory, raising [MultipartPartTooLargeException] past [limit]. */
private suspend fun ByteReadChannel.readCappedBytes(limit: Long): ByteArray {
    val buffer = Buffer()
    var total = 0L
    while (true) {
        val chunk = readRemaining(UPLOAD_CHUNK_BYTES)
        if (chunk.exhausted()) break
        total += buffer.transferFrom(chunk)
        if (total > limit) throw MultipartPartTooLargeException(limit)
    }
    return buffer.readByteArray()
}

/** Streams this channel to [dest] in [UPLOAD_CHUNK_BYTES] chunks, never buffering the full payload. */
private suspend fun ByteReadChannel.writeTo(dest: Path) {
    SystemFileSystem.sink(dest).buffered().use { sink ->
        while (true) {
            val chunk = readRemaining(UPLOAD_CHUNK_BYTES)
            if (chunk.exhausted()) break
            sink.transferFrom(chunk)
        }
    }
}
