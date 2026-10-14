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
internal actual suspend fun streamFirstFilePartTo(
    call: ApplicationCall,
    dest: Path,
    formFieldLimit: Long,
): Boolean {
    val boundary =
        call.request.contentType().parameter("boundary")
            ?: throw MalformedMultipartException("multipart/form-data request is missing a boundary parameter.")
    return streamFirstFilePart(call.receiveChannel(), boundary, formFieldLimit) {
        SystemFileSystem.sink(dest).buffered()
    }
}

/**
 * The CIO server's `receiveMultipart` transform is unavailable on Kotlin/Native (KTOR-7361), so this decodes the raw
 * body channel with [readImageUploadParts]. Behaviour matches the JVM actual.
 */
internal actual suspend fun receiveImageUploadParts(
    call: ApplicationCall,
    fileLimit: Long,
): ImageUploadParts {
    val boundary =
        call.request.contentType().parameter("boundary")
            ?: throw MalformedMultipartException("multipart/form-data request is missing a boundary parameter.")
    return readImageUploadParts(call.receiveChannel(), boundary, fileLimit)
}
