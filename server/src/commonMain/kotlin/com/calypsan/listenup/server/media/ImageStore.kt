package com.calypsan.listenup.server.media

import com.calypsan.listenup.server.io.fileIoDispatcher
import com.calypsan.listenup.server.io.hashBytesSha256
import com.calypsan.listenup.server.io.writeBytesAtomically
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Reusable validate-store-serve primitive for images, rooted at [baseDir]
 * (e.g. `$LISTENUP_HOME/avatars`). Validates the bytes are a real JPEG/PNG/WebP by magic number
 * (not the declared content type) and enforces [maxBytes]. Files are stored as `<key>.<ext>`.
 * Backs avatars, book covers, matched covers and contributor photos.
 */
class ImageStore(
    private val baseDir: Path,
    private val maxBytes: Long,
) {
    /** Thrown when the supplied bytes fail validation (wrong magic number or over-size). */
    class InvalidImageException(
        message: String,
    ) : Exception(message)

    /** The result of a successful [store] call. */
    data class StoredImage(
        val path: Path,
        val contentType: String,
        val sha256: String,
    )

    private val accepted = setOf(ImageKind.JPEG, ImageKind.PNG, ImageKind.WEBP)

    /**
     * Validates [bytes] by magic number, writes the file to `<baseDir>/<key>.<ext>`, and
     * returns a [StoredImage] describing the result. The new file lands atomically; only then are
     * the other extensions for [key] removed, so a failed write never destroys the previous image.
     *
     * @throws InvalidImageException if [bytes] exceed [maxBytes] or carry an unsupported magic number,
     * or if [key] would escape [baseDir] via path traversal.
     */
    suspend fun store(
        key: String,
        bytes: ByteArray,
        declaredContentType: String,
    ): StoredImage =
        withContext(fileIoDispatcher) {
            if (bytes.size.toLong() > maxBytes) throw InvalidImageException("image exceeds $maxBytes bytes")
            val kind = sniff(bytes) ?: throw InvalidImageException("unsupported image (declared=$declaredContentType)")
            SystemFileSystem.createDirectories(baseDir)
            val target =
                safeResolve(key, kind.ext)
                    ?: throw InvalidImageException("invalid key")
            target.writeBytesAtomically(bytes)
            deleteOtherExtensions(key, keep = kind)
            StoredImage(target, kind.contentType, hashBytesSha256(bytes))
        }

    /**
     * Stores [bytes] under a [key] derived from their content. The file is shared by every row that
     * holds the same picture, so nothing is ever removed and an existing file is left untouched.
     *
     * @throws InvalidImageException as [store] does.
     */
    suspend fun storeContentAddressed(
        key: String,
        bytes: ByteArray,
        declaredContentType: String,
    ): StoredImage =
        withContext(fileIoDispatcher) {
            if (bytes.size.toLong() > maxBytes) throw InvalidImageException("image exceeds $maxBytes bytes")
            val kind = sniff(bytes) ?: throw InvalidImageException("unsupported image (declared=$declaredContentType)")
            SystemFileSystem.createDirectories(baseDir)
            val target =
                safeResolve(key, kind.ext)
                    ?: throw InvalidImageException("invalid key")
            if (SystemFileSystem.metadataOrNull(target)?.isRegularFile != true) {
                target.writeBytesAtomically(bytes)
            }
            StoredImage(target, kind.contentType, hashBytesSha256(bytes))
        }

    /**
     * Returns the path to the stored image for [key], or `null` if no image has been stored
     * or if [key] is unsafe (contains `..`, `/`, or `\`).
     * Checks all supported extensions in order.
     */
    fun pathFor(key: String): Path? =
        accepted
            .mapNotNull { safeResolve(key, it.ext) }
            .firstOrNull { SystemFileSystem.metadataOrNull(it)?.isRegularFile == true }

    /**
     * Returns the MIME content type for a stored image [path] based on its extension,
     * or `"application/octet-stream"` if the extension is unrecognised.
     */
    fun contentTypeFor(path: Path): String =
        accepted.firstOrNull { path.name.endsWith(".${it.ext}") }?.contentType
            ?: "application/octet-stream"

    /** Removes the stored image for [key] if one exists. Idempotent. */
    suspend fun delete(key: String) {
        withContext(fileIoDispatcher) { deleteExisting(key) }
    }

    private fun deleteExisting(key: String) {
        accepted.forEach { kind ->
            safeResolve(key, kind.ext)?.let { SystemFileSystem.delete(it, mustExist = false) }
        }
    }

    private fun deleteOtherExtensions(
        key: String,
        keep: ImageKind,
    ) {
        accepted.filter { it != keep }.forEach { kind ->
            safeResolve(key, kind.ext)?.let { SystemFileSystem.delete(it, mustExist = false) }
        }
    }

    /**
     * Resolves `<baseDir>/<key>.<ext>`, returning `null` if [key] contains path-traversal
     * characters (`..`, `/`, `\`) or if the resolved path escapes [baseDir].
     */
    private fun safeResolve(
        key: String,
        ext: String,
    ): Path? {
        if (".." in key || "/" in key || "\\" in key) return null
        val resolved = Path(baseDir, "$key.$ext")
        return resolved.takeIf { it.toString().startsWith(baseDir.toString()) }
    }

    // GIF is deliberately outside the set: avatars and covers have never accepted it.
    private fun sniff(bytes: ByteArray): ImageKind? = ImageKind.sniff(bytes)?.takeIf { it in accepted }
}
