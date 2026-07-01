package com.calypsan.listenup.server.io

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/**
 * The Kotlin/Native Ktor CIO server cannot use Ktor's `receiveMultipart` transform (KTOR-7361), but
 * the raw body channel works on every engine — so this reads it and decodes multipart/form-data with
 * [streamFirstFilePart], streaming the first file part straight to [dest]. Behaviour matches the JVM
 * actual.
 */
internal actual suspend fun ApplicationCall.streamFirstFilePartTo(
    dest: Path,
    formFieldLimit: Long,
): Boolean {
    val boundary =
        request.contentType().parameter("boundary")
            ?: throw MalformedMultipartException("multipart/form-data request is missing a boundary parameter.")
    return streamFirstFilePart(receiveChannel(), boundary, formFieldLimit) {
        SystemFileSystem.sink(dest).buffered()
    }
}

/**
 * In-memory sibling of [streamFirstFilePartTo] for uploads that must be buffered for validation
 * (avatars). Decodes the raw body channel with [streamFirstFilePart] into a [Buffer] — the CIO
 * server's `receiveMultipart` transform is unavailable on Kotlin/Native (KTOR-7361).
 */
internal actual suspend fun ApplicationCall.receiveFirstFilePartBytes(formFieldLimit: Long): ByteArray? {
    val boundary =
        request.contentType().parameter("boundary")
            ?: throw MalformedMultipartException("multipart/form-data request is missing a boundary parameter.")
    val buffer = Buffer()
    val received = streamFirstFilePart(receiveChannel(), boundary, formFieldLimit) { buffer }
    return if (received) buffer.readByteArray() else null
}
