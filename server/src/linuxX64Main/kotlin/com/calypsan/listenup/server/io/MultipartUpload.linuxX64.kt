package com.calypsan.listenup.server.io

import io.ktor.server.application.ApplicationCall
import kotlinx.io.files.Path

/**
 * The Kotlin/Native Ktor CIO server cannot parse multipart request bodies (KTOR-7361), so it cannot
 * stream an upload. Fails loudly with [MultipartUploadUnsupported] (→ `501 Not Implemented`) rather
 * than silently dropping the body; uploads are served by the JVM runtime, which has a real actual.
 */
internal actual suspend fun ApplicationCall.streamFirstFilePartTo(
    dest: Path,
    formFieldLimit: Long,
): Boolean = throw MultipartUploadUnsupported()
