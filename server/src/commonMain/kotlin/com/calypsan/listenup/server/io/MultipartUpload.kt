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
internal expect suspend fun streamFirstFilePartTo(
    call: ApplicationCall,
    dest: Path,
    formFieldLimit: Long,
): Boolean

/**
 * Receives an image upload: the first file part's bytes, capped at [fileLimit] during the read, and the optional
 * [com.calypsan.listenup.api.dto.image.IMAGE_UPLOAD_FRAME_PART] form field, capped at [FRAME_FIELD_MAX_BYTES]. Every
 * other part is released. For small uploads (photos, avatars) that must be buffered for magic-number validation.
 *
 * Platform note: the JVM runtime delegates to Ktor's `receiveMultipart`; Kotlin/Native's Ktor CIO server cannot parse
 * multipart through that transform (KTOR-7361), so the native actual decodes the raw body with [readImageUploadParts].
 *
 * @throws MultipartPartTooLargeException if the file part exceeds [fileLimit].
 * @throws MultipartFieldTooLargeException if the frame field exceeds its cap.
 */
internal expect suspend fun receiveImageUploadParts(
    call: ApplicationCall,
    fileLimit: Long,
): ImageUploadParts
