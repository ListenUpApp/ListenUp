
package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.core.suspendRunCatching
import com.calypsan.listenup.client.domain.repository.ServerConfig
import io.ktor.client.call.body
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders

private const val COVER_REQUEST_TIMEOUT_MS = 60_000L
private const val TAR_BLOCK_SIZE = 512

/** Builds the multipart form-data body for a binary image upload (single `file` part). */
private fun imageFormData(
    imageData: ByteArray,
    filename: String,
) = formData {
    append(
        "file",
        imageData,
        Headers.build {
            append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
            append(HttpHeaders.ContentType, "image/*")
        },
    )
}

private fun seriesCoverPath(seriesId: String) = "/api/v1/series/$seriesId/cover"

/**
 * API client for image operations (download and upload).
 *
 * Handles communication with image endpoints:
 * - Downloads JPEG cover images from server
 * - Uploads book covers and contributor photos
 * - Uses multipart form data for uploads
 *
 * Uses ApiClientFactory to obtain authenticated HttpClient at call time,
 * avoiding runBlocking during dependency injection initialization.
 *
 * @property clientFactory Factory for creating authenticated HttpClient
 * @property settingsRepository For constructing full image URLs
 */
@NonRpcTransport(
    NonRpcReason.BINARY_TRANSFER,
    justification = "Cover/photo/avatar bytes and the TAR image batch are raw byte streams — no JSON-RPC frame.",
)
internal class ImageApi(
    private val clientFactory: ApiClientFactory,
    private val serverConfig: ServerConfig,
) : ImageApiContract {
    /**
     * Constructs a full URL from a relative path returned by the server.
     * Server returns paths like "/api/v1/contributors/xxx/image" but Coil
     * needs absolute URLs like "http://server:port/api/v1/contributors/xxx/image".
     */
    private suspend fun buildFullUrl(relativePath: String): String {
        // Active url, not local: covers/avatars must load from the remote host after a roam.
        val serverUrl = serverConfig.getActiveUrl()?.value ?: ""
        val path = relativePath.trimStart('/')
        return "$serverUrl/$path"
    }

    /**
     * Download cover image for a book.
     *
     * Returns raw JPEG bytes which can be saved to local storage
     * via ImageStorage. Returns failure if cover doesn't exist (404).
     *
     * Endpoint: GET /api/v1/covers/{bookId}
     * Auth: Not required (public access)
     * Response: image/jpeg (raw bytes)
     *
     * @param bookId Unique identifier for the book
     * @return Result containing image bytes or error
     */
    override suspend fun downloadCover(bookId: BookId): AppResult<ByteArray> =
        suspendRunCatching {
            val client = clientFactory.getClient()
            client
                .get("/api/v1/covers/${bookId.value}") {
                    timeout { requestTimeoutMillis = COVER_REQUEST_TIMEOUT_MS }
                }.body<ByteArray>()
        }

    /**
     * Download profile image for a contributor.
     *
     * Returns raw image bytes which can be saved to local storage
     * via ImageStorage. Returns failure if image doesn't exist (404).
     *
     * Endpoint: GET /api/v1/contributors/{contributorId}/photo
     * Auth: Required (Bearer token) — served inside the JWT scope by `MetadataImageRoutes`.
     * Response: image/jpeg (raw bytes)
     *
     * @param contributorId Unique identifier for the contributor
     * @return Result containing image bytes or error
     */
    override suspend fun downloadContributorImage(contributorId: String): AppResult<ByteArray> =
        suspendRunCatching {
            val client = clientFactory.getClient()
            client.get("/api/v1/contributors/$contributorId/photo").body<ByteArray>()
        }

    /**
     * Upload cover image for a book.
     *
     * Sends image as multipart form data with "file" field.
     * Server validates image format (JPEG, PNG, WebP, GIF) via magic bytes.
     *
     * Endpoint: PUT /api/v1/books/{bookId}/cover
     * Auth: Required (Bearer token)
     * Request: multipart/form-data with "file" field
     * Response: 204 No Content on success (no body).
     *
     * @param bookId Unique identifier for the book
     * @param imageData Raw image bytes
     * @param filename Original filename (used for content disposition)
     * @return Result carrying the canonical cover GET URL on success, or a typed error
     */
    override suspend fun uploadBookCover(
        bookId: String,
        imageData: ByteArray,
        filename: String,
    ): AppResult<ImageUploadResponse> =
        suspendRunCatching {
            val client = clientFactory.getClient()
            client.submitFormWithBinaryData(
                url = "/api/v1/books/$bookId/cover",
                formData = imageFormData(imageData, filename),
            ) {
                method = io.ktor.http.HttpMethod.Put
            }
            // Server responds 204 No Content on success; expectSuccess raises on non-2xx.
            // The canonical GET path is the stable client-side cache URL.
            ImageUploadResponse(imageUrl = buildFullUrl("/api/v1/covers/$bookId"))
        }

    /**
     * Upload profile image for a contributor.
     *
     * Sends image as multipart form data with "file" field.
     * Server validates image format (JPEG, PNG, WebP, GIF) via magic bytes.
     *
     * Endpoint: PUT /api/v1/contributors/{contributorId}/image
     * Auth: Required (Bearer token)
     * Request: multipart/form-data with "file" field
     * Response: 204 No Content on success (no body).
     *
     * @param contributorId Unique identifier for the contributor
     * @param imageData Raw image bytes
     * @param filename Original filename (used for content disposition)
     * @return Result carrying the canonical photo GET URL on success, or a typed error
     */
    override suspend fun uploadContributorImage(
        contributorId: String,
        imageData: ByteArray,
        filename: String,
    ): AppResult<ImageUploadResponse> =
        suspendRunCatching {
            val client = clientFactory.getClient()
            client.submitFormWithBinaryData(
                url = "/api/v1/contributors/$contributorId/image",
                formData = imageFormData(imageData, filename),
            ) {
                method = io.ktor.http.HttpMethod.Put
            }
            // Server responds 204 No Content on success; expectSuccess raises on non-2xx.
            ImageUploadResponse(imageUrl = buildFullUrl("/api/v1/contributors/$contributorId/photo"))
        }

    /**
     * Download cover image for a series.
     *
     * Returns raw image bytes which can be saved to local storage
     * via ImageStorage. Returns failure if cover doesn't exist (404).
     *
     * Endpoint: GET /api/v1/series/{seriesId}/cover
     * Auth: Not required (public access)
     * Response: image/jpeg (raw bytes)
     *
     * @param seriesId Unique identifier for the series
     * @return Result containing image bytes or error
     */
    override suspend fun downloadSeriesCover(seriesId: String): AppResult<ByteArray> =
        suspendRunCatching {
            val client = clientFactory.getClient()
            client.get(seriesCoverPath(seriesId)).body<ByteArray>()
        }

    /**
     * Upload cover image for a series.
     *
     * Sends image as multipart form data with "file" field.
     * Server validates image format (JPEG, PNG, WebP, GIF) via magic bytes.
     *
     * Endpoint: PUT /api/v1/series/{seriesId}/cover
     * Auth: Required (Bearer token)
     * Request: multipart/form-data with "file" field
     * Response: 204 No Content on success (no body).
     *
     * @param seriesId Unique identifier for the series
     * @param imageData Raw image bytes
     * @param filename Original filename (used for content disposition)
     * @return Result carrying the canonical cover GET URL on success, or a typed error
     */
    override suspend fun uploadSeriesCover(
        seriesId: String,
        imageData: ByteArray,
        filename: String,
    ): AppResult<ImageUploadResponse> =
        suspendRunCatching {
            val client = clientFactory.getClient()
            client.submitFormWithBinaryData(
                url = seriesCoverPath(seriesId),
                formData = imageFormData(imageData, filename),
            ) {
                method = io.ktor.http.HttpMethod.Put
            }
            // Server responds 204 No Content on success; expectSuccess raises on non-2xx.
            ImageUploadResponse(imageUrl = buildFullUrl(seriesCoverPath(seriesId)))
        }

    /**
     * Download multiple contributor images in a single request.
     *
     * Server returns a TAR stream containing all requested images.
     * Missing images are silently skipped by the server.
     * Each entry in the TAR is named `{contributorId}.jpg`.
     *
     * Endpoint: GET /api/v1/contributors/images/batch?ids=contrib_1,contrib_2
     * Auth: Required (Bearer token)
     * Response: application/x-tar (TAR archive)
     *
     * @param contributorIds List of contributor IDs to download images for (max 100)
     * @return Result containing map of contributorId to image bytes for successfully downloaded images
     */
    override suspend fun downloadContributorImageBatch(
        contributorIds: List<String>,
    ): AppResult<Map<String, ByteArray>> =
        suspendRunCatching {
            val client = clientFactory.getClient()
            val idsParam = contributorIds.joinToString(",")
            val tarData = client.get("/api/v1/contributors/images/batch?ids=$idsParam").body<ByteArray>()
            parseTar(tarData)
        }

    /**
     * Download avatar image for a user.
     *
     * Returns raw image bytes which can be saved to local storage
     * via ImageStorage. Returns failure if avatar doesn't exist (404).
     *
     * Endpoint: GET /api/v1/avatars/{userId}
     * Auth: bearer token (the authed client); any authenticated user may fetch any avatar (presence).
     * Response: image bytes (jpeg/png/webp); 404 when the user has no uploaded avatar.
     *
     * @param userId Unique identifier for the user
     * @return Result containing image bytes or error
     */
    override suspend fun downloadUserAvatar(userId: String): AppResult<ByteArray> =
        suspendRunCatching {
            val client = clientFactory.getClient()
            client.get("/api/v1/avatars/$userId").body<ByteArray>()
        }

    /**
     * Parse TAR archive and extract files.
     *
     * TAR format:
     * - Each file has a 512-byte header
     * - Filename at offset 0 (100 bytes, null-terminated)
     * - File size at offset 124 (12 bytes, octal ASCII)
     * - File data follows, padded to 512-byte boundary
     * - Archive ends with two 512-byte blocks of zeros
     *
     * @param tarData Raw TAR archive bytes
     * @return Map of filename (without extension) to file bytes
     */
    private fun parseTar(tarData: ByteArray): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        var offset = 0

        while (offset + TAR_BLOCK_SIZE <= tarData.size) {
            // Check if we've hit the end marker (two zero blocks)
            if (isZeroBlock(tarData, offset)) {
                break
            }

            // Extract filename (first 100 bytes, null-terminated)
            val filenameBytes = tarData.copyOfRange(offset, offset + 100)
            val filenameEndIndex = filenameBytes.indexOfFirst { it == 0.toByte() }
            val filename =
                if (filenameEndIndex >= 0) {
                    filenameBytes.copyOfRange(0, filenameEndIndex).decodeToString()
                } else {
                    filenameBytes.decodeToString()
                }

            // Extract file size (12 bytes at offset 124, octal ASCII)
            val sizeBytes = tarData.copyOfRange(offset + 124, offset + 136)
            val sizeStr = sizeBytes.takeWhile { it != 0.toByte() && it != 32.toByte() }.toByteArray().decodeToString()
            val fileSize =
                if (sizeStr.isNotEmpty()) {
                    sizeStr.trim().toLongOrNull(8)?.toInt() ?: 0
                } else {
                    0
                }

            // Move past header to file data
            offset += TAR_BLOCK_SIZE

            // Extract file data
            if (fileSize > 0 && offset + fileSize <= tarData.size) {
                val fileData = tarData.copyOfRange(offset, offset + fileSize)

                // Extract book ID from filename (remove .jpg extension)
                val bookId = filename.removeSuffix(".jpg")
                result[bookId] = fileData

                // Move to next file (files are padded to 512-byte boundary)
                val paddedSize = (fileSize + 511) / 512 * 512
                offset += paddedSize
            } else {
                // Invalid file size or truncated data, skip
                break
            }
        }

        return result
    }

    /**
     * Check if a 512-byte block is all zeros (end marker).
     */
    private fun isZeroBlock(
        data: ByteArray,
        offset: Int,
    ): Boolean {
        val endOffset = minOf(offset + TAR_BLOCK_SIZE, data.size)
        for (i in offset until endOffset) {
            if (data[i] != 0.toByte()) {
                return false
            }
        }
        return true
    }
}
