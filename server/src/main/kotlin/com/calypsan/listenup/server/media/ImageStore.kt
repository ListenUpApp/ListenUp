package com.calypsan.listenup.server.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Reusable validate-store-serve primitive for user-uploaded images, rooted at [baseDir]
 * (e.g. `$LISTENUP_HOME/avatars`). Validates the bytes are a real JPEG/PNG/WebP by magic number
 * (not the declared content type) and enforces [maxBytes]. Files are stored as `<key>.<ext>`.
 * Designed to back avatars now and book covers later.
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

    private enum class Kind(
        val ext: String,
        val contentType: String,
    ) {
        JPEG("jpg", "image/jpeg"),
        PNG("png", "image/png"),
        WEBP("webp", "image/webp"),
    }

    /**
     * Validates [bytes] by magic number, writes the file to `<baseDir>/<key>.<ext>`, and
     * returns a [StoredImage] describing the result. Any previously stored image for [key]
     * is replaced atomically (delete-then-write).
     *
     * @throws InvalidImageException if [bytes] exceed [maxBytes] or carry an unsupported magic number.
     */
    suspend fun store(
        key: String,
        bytes: ByteArray,
        declaredContentType: String,
    ): StoredImage =
        withContext(Dispatchers.IO) {
            if (bytes.size.toLong() > maxBytes) throw InvalidImageException("image exceeds $maxBytes bytes")
            val kind = sniff(bytes) ?: throw InvalidImageException("unsupported image (declared=$declaredContentType)")
            Files.createDirectories(baseDir)
            deleteExisting(key)
            val target = baseDir.resolve("$key.${kind.ext}")
            Files.write(target, bytes)
            StoredImage(target, kind.contentType, sha256(bytes))
        }

    /**
     * Returns the path to the stored image for [key], or `null` if no image has been stored.
     * Checks all supported extensions in order.
     */
    fun pathFor(key: String): Path? =
        Kind.entries
            .map { baseDir.resolve("$key.${it.ext}") }
            .firstOrNull { Files.isRegularFile(it) }

    /**
     * Returns the MIME content type for a stored image [path] based on its extension,
     * or `"application/octet-stream"` if the extension is unrecognised.
     */
    fun contentTypeFor(path: Path): String =
        Kind.entries.firstOrNull { path.fileName.toString().endsWith(".${it.ext}") }?.contentType
            ?: "application/octet-stream"

    /** Removes the stored image for [key] if one exists. Idempotent. */
    suspend fun delete(key: String) {
        withContext(Dispatchers.IO) { deleteExisting(key) }
    }

    private fun deleteExisting(key: String) {
        Kind.entries.forEach { Files.deleteIfExists(baseDir.resolve("$key.${it.ext}")) }
    }

    private fun sniff(bytes: ByteArray): Kind? {
        if (bytes.size < MIN_SNIFF_BYTES) return null
        if (isJpeg(bytes)) return Kind.JPEG
        if (isPng(bytes)) return Kind.PNG
        if (isWebp(bytes)) return Kind.WEBP
        return null
    }

    private fun isJpeg(b: ByteArray): Boolean =
        b[OFFSET_0] == JPEG_BYTE_0 && b[OFFSET_1] == JPEG_BYTE_1 && b[OFFSET_2] == JPEG_BYTE_2

    private fun isPng(b: ByteArray): Boolean =
        b[OFFSET_0] == PNG_BYTE_0 && b[OFFSET_1] == PNG_BYTE_1 && b[OFFSET_2] == PNG_BYTE_2 && b[OFFSET_3] == PNG_BYTE_3

    private fun isWebp(b: ByteArray): Boolean =
        b.size >= WEBP_MIN_BYTES &&
            b[OFFSET_0] == WEBP_RIFF_0 && b[OFFSET_1] == WEBP_RIFF_1 &&
            b[OFFSET_2] == WEBP_RIFF_2 && b[OFFSET_3] == WEBP_RIFF_3 &&
            b[WEBP_OFFSET_8] == WEBP_SIG_8 && b[WEBP_OFFSET_9] == WEBP_SIG_9 &&
            b[WEBP_OFFSET_10] == WEBP_SIG_10 && b[WEBP_OFFSET_11] == WEBP_SIG_11

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance(SHA256_ALGORITHM).digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MIN_SNIFF_BYTES = 4
        const val WEBP_MIN_BYTES = 12

        // Byte offsets used for magic-number probing
        const val OFFSET_0 = 0
        const val OFFSET_1 = 1
        const val OFFSET_2 = 2
        const val OFFSET_3 = 3
        const val WEBP_OFFSET_8 = 8
        const val WEBP_OFFSET_9 = 9
        const val WEBP_OFFSET_10 = 10
        const val WEBP_OFFSET_11 = 11

        // JPEG magic: FF D8 FF
        val JPEG_BYTE_0: Byte = 0xFF.toByte()
        val JPEG_BYTE_1: Byte = 0xD8.toByte()
        val JPEG_BYTE_2: Byte = 0xFF.toByte()

        // PNG magic: 89 50 4E 47
        val PNG_BYTE_0: Byte = 0x89.toByte()
        val PNG_BYTE_1: Byte = 0x50.toByte()
        val PNG_BYTE_2: Byte = 0x4E.toByte()
        val PNG_BYTE_3: Byte = 0x47.toByte()

        // WebP magic: "RIFF" at 0..3, "WEBP" at 8..11
        val WEBP_RIFF_0: Byte = 'R'.code.toByte()
        val WEBP_RIFF_1: Byte = 'I'.code.toByte()
        val WEBP_RIFF_2: Byte = 'F'.code.toByte()
        val WEBP_RIFF_3: Byte = 'F'.code.toByte()
        val WEBP_SIG_8: Byte = 'W'.code.toByte()
        val WEBP_SIG_9: Byte = 'E'.code.toByte()
        val WEBP_SIG_10: Byte = 'B'.code.toByte()
        val WEBP_SIG_11: Byte = 'P'.code.toByte()

        const val SHA256_ALGORITHM = "SHA-256"
    }
}
