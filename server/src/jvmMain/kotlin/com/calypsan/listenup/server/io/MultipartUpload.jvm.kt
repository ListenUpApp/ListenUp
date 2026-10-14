package com.calypsan.listenup.server.io

import com.calypsan.listenup.api.dto.image.IMAGE_UPLOAD_FRAME_PART
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveMultipart
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readBuffer
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/** Bounded read size for streaming an upload to disk — never holds the whole body in memory. */
private const val UPLOAD_CHUNK_BYTES: Long = 64L * 1024

internal actual suspend fun streamFirstFilePartTo(
    call: ApplicationCall,
    dest: Path,
    formFieldLimit: Long,
): Boolean {
    var received = false
    call.receiveMultipart(formFieldLimit = formFieldLimit).forEachPart { part ->
        if (part is PartData.FileItem && !received) {
            received = true
            part.provider().writeTo(dest)
        }
        part.release()
    }
    return received
}

internal actual suspend fun receiveImageUploadParts(
    call: ApplicationCall,
    fileLimit: Long,
): ImageUploadParts {
    var file: ByteArray? = null
    var frameField: String? = null
    // Ktor's default formFieldLimit (50 MiB) governs the transform; our caps fire deterministically below, matching
    // the native decoder's contract.
    call.receiveMultipart().forEachPart { part ->
        try {
            when {
                part is PartData.FileItem && file == null -> {
                    file = part.provider().readCappedBytes(fileLimit)
                }

                part is PartData.FormItem && part.name == IMAGE_UPLOAD_FRAME_PART && frameField == null -> {
                    val text = part.value
                    if (text.encodeToByteArray().size > FRAME_FIELD_MAX_BYTES) {
                        throw MultipartFieldTooLargeException(IMAGE_UPLOAD_FRAME_PART, FRAME_FIELD_MAX_BYTES)
                    }
                    frameField = text
                }

                else -> {
                    Unit
                }
            }
        } finally {
            part.release()
        }
    }
    return ImageUploadParts(file, frameField)
}

/** Reads this channel fully into memory, raising [MultipartPartTooLargeException] past [limit]. */
private suspend fun ByteReadChannel.readCappedBytes(limit: Long): ByteArray {
    val buffer = Buffer()
    var total = 0L
    while (true) {
        val chunk = readBuffer(UPLOAD_CHUNK_BYTES)
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
            val chunk = readBuffer(UPLOAD_CHUNK_BYTES)
            if (chunk.exhausted()) break
            sink.transferFrom(chunk)
        }
    }
}
