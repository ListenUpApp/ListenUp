package com.calypsan.listenup.server.io

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

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
