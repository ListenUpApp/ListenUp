package com.calypsan.listenup.server.routes

import com.calypsan.listenup.server.io.hashBytesSha256
import com.calypsan.listenup.server.metadata.ImageStorage
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveMultipart
import io.ktor.utils.io.toByteArray
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** Maximum accepted image size for the pre-buffer Content-Length check (10 MiB). Mirrors the book cover cap. */
private const val IMAGE_MAX_BYTES = 10L * 1024 * 1024

/**
 * Outcome of reading, validating, and storing a multipart image upload.
 *
 * [Stored] carries the content-addressed relative path to persist on the domain row; [Rejected]
 * carries the HTTP status + message the route should answer without touching the row.
 */
internal sealed interface ImageUploadOutcome {
    /** The image was accepted and written; [relPath] is the `<subdir>/<sha256>.jpg` path to persist. */
    data class Stored(
        val relPath: String,
    ) : ImageUploadOutcome

    /** The upload was rejected before any row write; answer [status] with [message]. */
    data class Rejected(
        val status: HttpStatusCode,
        val message: String,
    ) : ImageUploadOutcome
}

/**
 * Reads the first file part of a multipart upload, enforces the [IMAGE_MAX_BYTES] cap BEFORE buffering
 * (via the declared `Content-Length`), validates the bytes carry a recognised image magic number, and
 * writes them content-addressed to `<imageHome>/<subdir>/<sha256>.jpg` via [imageStorage].
 *
 * Content-addressing is deliberate: a replacement image yields a new relative path, which is exactly
 * what clients key their image cache on (the path is the version). Returns 413 for an oversize declared
 * part, 400 when no file part is present, 422 when the bytes fail the image magic-number check, and
 * [ImageUploadOutcome.Stored] with the relative path on success.
 *
 * The caller persists the path through the principal-scoped service so its internal `requireCanEdit`
 * gate + revision bump + sync-event publication fire — this helper does not gate permissions.
 */
internal suspend fun ApplicationCall.storeMultipartImage(
    subdir: String,
    imageHome: Path,
    imageStorage: ImageStorage,
): ImageUploadOutcome {
    var bytes: ByteArray? = null
    var oversized = false
    receiveMultipart().forEachPart { part ->
        if (part is PartData.FileItem && bytes == null) {
            val declaredLength = part.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (declaredLength != null && declaredLength > IMAGE_MAX_BYTES) {
                part.release()
                oversized = true
                return@forEachPart
            }
            bytes = part.provider().toByteArray()
        }
        part.release()
    }

    if (oversized) return ImageUploadOutcome.Rejected(HttpStatusCode.PayloadTooLarge, "file too large")
    val data = bytes ?: return ImageUploadOutcome.Rejected(HttpStatusCode.BadRequest, "missing file part")
    // A part without a declared Content-Length still can't exceed the cap once buffered.
    if (data.size >
        IMAGE_MAX_BYTES
    ) {
        return ImageUploadOutcome.Rejected(HttpStatusCode.PayloadTooLarge, "file too large")
    }
    if (!isRecognisedImage(data)) {
        return ImageUploadOutcome.Rejected(HttpStatusCode.UnprocessableEntity, "invalid image")
    }

    val relPath = "$subdir/${hashBytesSha256(data)}.jpg"
    SystemFileSystem.createDirectories(Path(imageHome.toString(), subdir))
    imageStorage.writeBytes(data, Path(imageHome.toString(), relPath))
    return ImageUploadOutcome.Stored(relPath)
}

/**
 * Sniffs [bytes] for a supported image magic number (JPEG, PNG, GIF, WebP) — the same set the book
 * cover upload accepts. A minimal inline check: the metadata routes don't reach the book-cover
 * `ImageStore` (its validation is inseparable from the managed cover-path record).
 */
private fun isRecognisedImage(bytes: ByteArray): Boolean {
    if (bytes.size < MIN_MAGIC_BYTES) return false
    // JPEG: FF D8 FF
    if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) return true
    // PNG: 89 50 4E 47
    if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() &&
        bytes[3] == 0x47.toByte()
    ) {
        return true
    }
    // GIF: "GIF8"
    if (bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() &&
        bytes[3] == '8'.code.toByte()
    ) {
        return true
    }
    // WebP: "RIFF" .... "WEBP" — the "WEBP" tag sits at byte offset 8, after the 4-byte "RIFF"
    // marker and the 4-byte little-endian file-size field.
    return bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() &&
        bytes[3] == 'F'.code.toByte() && bytes[WEBP_TAG_OFFSET] == 'W'.code.toByte() &&
        bytes[WEBP_TAG_OFFSET + 1] == 'E'.code.toByte() && bytes[WEBP_TAG_OFFSET + 2] == 'B'.code.toByte() &&
        bytes[WEBP_TAG_OFFSET + 3] == 'P'.code.toByte()
}

/** Byte offset of the "WEBP" tag within a RIFF container (after "RIFF" + the 4-byte size field). */
private const val WEBP_TAG_OFFSET = 8

/** WebP needs 12 bytes to check; the shorter signatures fit inside this window too. */
private const val MIN_MAGIC_BYTES = 12
