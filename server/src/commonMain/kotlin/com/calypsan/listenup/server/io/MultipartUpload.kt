package com.calypsan.listenup.server.io

import io.ktor.server.application.ApplicationCall
import kotlinx.io.files.Path

/**
 * Receives a multipart request and streams the first file part to [dest] chunk by chunk, so a
 * multi-GiB upload never lands in memory. Returns true when a file part was present; non-file parts
 * and any parts after the first are released and ignored. [formFieldLimit] caps the accepted size
 * (Ktor's 50 MiB default rejects large library backups before they can be staged). [dest] is
 * overwritten; its parent directory must exist.
 *
 * Platform note: the JVM runtime streams the upload. Kotlin/Native's Ktor CIO server cannot parse
 * multipart bodies (JetBrains [KTOR-7361](https://youtrack.jetbrains.com/issue/KTOR-7361)), so the
 * native actual throws [MultipartUploadUnsupported], which [installAppErrorStatusPages] surfaces as
 * `501 Not Implemented`. The native server is a compile/boot target; the JVM runtime serves uploads.
 */
internal expect suspend fun ApplicationCall.streamFirstFilePartTo(
    dest: Path,
    formFieldLimit: Long,
): Boolean

/**
 * Signals that a multipart upload was attempted on the Kotlin/Native server runtime, whose Ktor CIO
 * engine cannot parse multipart bodies (KTOR-7361). Thrown by the native [streamFirstFilePartTo]
 * actual and surfaced as `501 Not Implemented` by [installAppErrorStatusPages].
 */
internal class MultipartUploadUnsupported :
    UnsupportedOperationException("Multipart upload is not supported on the native server runtime.")
