package com.calypsan.listenup.server.io

import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveMultipart
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

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
