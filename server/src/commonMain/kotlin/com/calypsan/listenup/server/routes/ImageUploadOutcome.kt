package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.image.ImageFrame
import com.calypsan.listenup.api.dto.image.invalidImageFrame
import com.calypsan.listenup.api.dto.image.validate
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.server.io.MultipartFieldTooLargeException
import com.calypsan.listenup.server.io.MultipartPartTooLargeException
import com.calypsan.listenup.server.io.hashBytesSha256
import com.calypsan.listenup.server.io.receiveImageUploadParts
import com.calypsan.listenup.server.media.ImageKind
import com.calypsan.listenup.server.metadata.ImageStorage
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.SerializationException

/** Maximum accepted image size, enforced during the multipart read (10 MiB). Mirrors the book cover cap. */
private const val IMAGE_MAX_BYTES = 10L * 1024 * 1024

/**
 * Outcome of reading, validating, and storing a multipart image upload.
 *
 * [Stored] carries the content-addressed relative path to persist on the domain row, and the frame the upload
 * carried; [Rejected] carries the HTTP status + message the route should answer without touching the row;
 * [InvalidFrame] carries the typed error for an unreadable or out-of-range `frame` field.
 */
internal sealed interface ImageUploadOutcome {
    /** The image was accepted and written; [relPath] is `<subdir>/<sha256>.<ext>`, and [frame] what the upload carried. */
    data class Stored(
        val relPath: String,
        val frame: ImageFrame?,
    ) : ImageUploadOutcome

    /** The upload was rejected before any row write; answer [status] with [message]. */
    data class Rejected(
        val status: HttpStatusCode,
        val message: String,
    ) : ImageUploadOutcome

    /** The upload's `frame` field was unreadable or out of range; nothing was stored. Answer [error]. */
    data class InvalidFrame(
        val error: ValidationError,
    ) : ImageUploadOutcome
}

/** An image upload's optional `frame` field, decoded and range-checked. */
internal sealed interface FramePart {
    /** The upload carried no frame. */
    data object Absent : FramePart

    /** A frame in range. */
    data class Valid(
        val frame: ImageFrame,
    ) : FramePart

    /** A frame that cannot be decoded or is out of range. */
    data class Invalid(
        val error: ValidationError,
    ) : FramePart
}

/** Decodes [field] as [ImageFrame] JSON and range-checks it; a null field is [FramePart.Absent]. */
internal fun framePartOf(field: String?): FramePart {
    if (field == null) return FramePart.Absent
    val frame =
        try {
            contractJson.decodeFromString(ImageFrame.serializer(), field)
        } catch (e: SerializationException) {
            return FramePart.Invalid(invalidImageFrame(e.message))
        }
    return frame.validate()?.let { FramePart.Invalid(it) } ?: FramePart.Valid(frame)
}

/**
 * Reads the first file part of a multipart upload, enforces the [IMAGE_MAX_BYTES] cap DURING the read
 * (the streaming primitive aborts before an oversize part fully lands in memory — regardless of whether
 * the part declares a `Content-Length`), validates the bytes carry a recognised image magic number, and
 * writes them content-addressed to `<imageHome>/<subdir>/<sha256>.<ext>` via [imageStorage].
 *
 * Content-addressing is deliberate: a replacement image yields a new relative path, which is exactly
 * what clients key their image cache on (the path is the version). Returns 413 for an oversize part,
 * 400 when no file part is present, 422 when the bytes fail the image magic-number check, and
 * [ImageUploadOutcome.Stored] with the relative path on success.
 *
 * The optional `frame` field is decoded and range-checked **before** anything is stored; an unreadable or
 * out-of-range one is [ImageUploadOutcome.InvalidFrame].
 *
 * The caller persists the path through the principal-scoped service so its internal `requirePermission(Permission.EDIT_METADATA)`
 * gate + revision bump + sync-event publication fire — this helper does not gate permissions.
 */
internal suspend fun storeMultipartImage(
    call: ApplicationCall,
    subdir: String,
    imageHome: Path,
    imageStorage: ImageStorage,
): ImageUploadOutcome {
    val parts =
        try {
            receiveImageUploadParts(call, IMAGE_MAX_BYTES)
        } catch (e: MultipartPartTooLargeException) {
            return ImageUploadOutcome.Rejected(HttpStatusCode.PayloadTooLarge, "file exceeds ${e.limit} bytes")
        } catch (e: MultipartFieldTooLargeException) {
            return ImageUploadOutcome.InvalidFrame(invalidImageFrame(e.message))
        }
    val data = parts.file ?: return ImageUploadOutcome.Rejected(HttpStatusCode.BadRequest, "missing file part")
    val frame =
        when (val part = framePartOf(parts.frameField)) {
            FramePart.Absent -> null
            is FramePart.Valid -> part.frame
            is FramePart.Invalid -> return ImageUploadOutcome.InvalidFrame(part.error)
        }
    val kind =
        ImageKind.sniff(data)
            ?: return ImageUploadOutcome.Rejected(HttpStatusCode.UnprocessableEntity, "invalid image")

    val relPath = "$subdir/${hashBytesSha256(data)}.${kind.ext}"
    SystemFileSystem.createDirectories(Path(imageHome.toString(), subdir))
    imageStorage.writeBytes(data, Path(imageHome.toString(), relPath))
    return ImageUploadOutcome.Stored(relPath, frame)
}
