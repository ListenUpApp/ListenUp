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
 * Platform note: the JVM runtime delegates to Ktor's `receiveMultipart`. Kotlin/Native's Ktor CIO
 * server cannot parse multipart through that transform (JetBrains
 * [KTOR-7361](https://youtrack.jetbrains.com/issue/KTOR-7361)), so the native actual reads the raw
 * body channel and decodes the wire format itself via [streamFirstFilePart]. Both runtimes serve
 * uploads with identical behaviour.
 */
internal expect suspend fun ApplicationCall.streamFirstFilePartTo(
    dest: Path,
    formFieldLimit: Long,
): Boolean
