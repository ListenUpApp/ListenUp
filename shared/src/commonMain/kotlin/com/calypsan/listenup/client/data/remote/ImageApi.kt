@file:Suppress("StringLiteralDuplication", "MagicNumber")

package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.client.core.BookId
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.core.suspendRunCatching
import com.calypsan.listenup.client.data.remote.model.ApiResponse
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.core.error.AppException
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
class ImageApi(
    private val clientFactory: ApiClientFactory,
    private val serverConfig: ServerConfig,
) : ImageApiContract {
    /**
     * Constructs a full URL from a relative path returned by the server.
     * Server returns paths like "/api/v1/contributors/xxx/image" but Coil
     * needs absolute URLs like "http://server:port/api/v1/contributors/xxx/image".
     */
    private suspend fun buildFullUrl(relativePath: String): String {
        val serverUrl = serverConfig.getServerUrl()?.value ?: ""
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
                    timeout { requestTimeoutMillis = 60_000 }
                }.body<ByteArray>()
        }

    /**
     * Download profile image for a contributor.
     *
     * Returns raw image bytes which can be saved to local storage
     * via ImageStorage. Returns failure if image doesn't exist (404).
     *
     * Endpoint: GET /api/v1/contributors/{contributorId}/image
     * Auth: Not required (public access)
     * Response: image/jpeg (raw bytes)
     *
     * @param contributorId Unique identifier for the contributor
     * @return Result containing image bytes or error
     */
    override suspend fun downloadContributorImage(contributorId: String): AppResult<ByteArray> =
        suspendRunCatching {
            val client = clientFactory.getClient()
            client.get("/api/v1/contributors/$contributorId/image").body<ByteArray>()
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
     *
     * @param bookId Unique identifier for the book
     * @param imageData Raw image bytes
     * @param filename Original filename (used for content disposition)
     * @return Result containing the image URL or error
     */
    override suspend fun uploadBookCover(
        bookId: String,
        imageData: ByteArray,
        filename: String,
    ): AppResult<ImageUploadResponse> =
        suspendRunCatching {
            val client = clientFactory.getClient()
            val response: ApiResponse<ImageUploadApiResponse> =
                client
                    .submitFormWithBinaryData(
                        url = "/api/v1/books/$bookId/cover",
                        formData =
                            formData {
                                append(
                                    "file",
                                    imageData,
                                    Headers.build {
                                        append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
                                        append(HttpHeaders.ContentType, "image/*")
                                    },
                                )
                            },
                    ) {
                        method = io.ktor.http.HttpMethod.Put
                    }.body()

            when (val result = response.toResult()) {
                is Success -> {
                    val apiResponse = result.data
                    val relativeUrl = apiResponse.imageUrl ?: apiResponse.coverUrl ?: ""
                    ImageUploadResponse(imageUrl = buildFullUrl(relativeUrl))
                }

                is Failure -> {
                    throw AppException(result.error)
                }
            }
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
     *
     * @param contributorId Unique identifier for the contributor
     * @param imageData Raw image bytes
     * @param filename Original filename (used for content disposition)
     * @return Result containing the image URL or error
     */
    override suspend fun uploadContributorImage(
        contributorId: String,
        imageData: ByteArray,
        filename: String,
    ): AppResult<ImageUploadResponse> =
        suspendRunCatching {
            val client = clientFactory.getClient()
            val response: ApiResponse<ImageUploadApiResponse> =
                client
                    .submitFormWithBinaryData(
                        url = "/api/v1/contributors/$contributorId/image",
                        formData =
                            formData {
                                append(
                                    "file",
                                    imageData,
                                    Headers.build {
                                        append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
                                        append(HttpHeaders.ContentType, "image/*")
                                    },
                                )
                            },
                    ) {
                        method = io.ktor.http.HttpMethod.Put
                    }.body()

            when (val result = response.toResult()) {
                is Success -> {
                    val apiResponse = result.data
                    val relativeUrl = apiResponse.imageUrl ?: apiResponse.coverUrl ?: ""
                    ImageUploadResponse(imageUrl = buildFullUrl(relativeUrl))
                }

                is Failure -> {
                    throw AppException(result.error)
                }
            }
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
            client.get("/api/v1/series/$seriesId/cover").body<ByteArray>()
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
     *
     * @param seriesId Unique identifier for the series
     * @param imageData Raw image bytes
     * @param filename Original filename (used for content disposition)
     * @return Result containing the image URL or error
     */
    override suspend fun uploadSeriesCover(
        seriesId: String,
        imageData: ByteArray,
        filename: String,
    ): AppResult<ImageUploadResponse> =
        suspendRunCatching {
            val client = clientFactory.getClient()
            val response: ApiResponse<ImageUploadApiResponse> =
                client
                    .submitFormWithBinaryData(
                        url = "/api/v1/series/$seriesId/cover",
                        formData =
                            formData {
                                append(
                                    "file",
                                    imageData,
                                    Headers.build {
                                        append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
                                        append(HttpHeaders.ContentType, "image/*")
                                    },
                                )
                            },
                    ) {
                        method = io.ktor.http.HttpMethod.Put
                    }.body()

            when (val result = response.toResult()) {
                is Success -> {
                    val apiResponse = result.data
                    val relativeUrl = apiResponse.imageUrl ?: apiResponse.coverUrl ?: ""
                    ImageUploadResponse(imageUrl = buildFullUrl(relativeUrl))
                }

                is Failure -> {
                    throw AppException(result.error)
                }
            }
        }

    /**
     * Delete cover image for a series.
     *
     * Removes the cover image from the server.
     *
     * Endpoint: DELETE /api/v1/series/{seriesId}/cover
     * Auth: Required (Bearer token)
     * Response: 204 No Content
     *
     * @param seriesId Unique identifier for the series
     * @return Result with Unit on success or error
     */
    override suspend fun deleteSeriesCover(seriesId: String): AppResult<Unit> =
        suspendRunCatching {
            val client = clientFactory.getClient()
            val response: ApiResponse<Unit> = client.delete("/api/v1/series/$seriesId/cover").body()

            when (val result = response.toResult()) {
                is Success -> { /* Cover deleted successfully */ }

                is Failure -> {
                    throw AppException(result.error)
                }
            }
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
     * Endpoint: GET /avatars/{userId}
     * Note: This endpoint is NOT under /api/v1 - it's a static file server route.
     * Auth: Not required (public access)
     * Response: image/jpeg (raw bytes)
     *
     * @param userId Unique identifier for the user
     * @return Result containing image bytes or error
     */
    override suspend fun downloadUserAvatar(userId: String): AppResult<ByteArray> =
        suspendRunCatching {
            val client = clientFactory.getClient()
            client.get("/avatars/$userId").body<ByteArray>()
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

        while (offset + 512 <= tarData.size) {
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
            offset += 512

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
        val endOffset = minOf(offset + 512, data.size)
        for (i in offset until endOffset) {
            if (data[i] != 0.toByte()) {
                return false
            }
        }
        return true
    }
}

/**
 * API response for image upload.
 */
@Serializable
private data class ImageUploadApiResponse(
    @SerialName("cover_url")
    val coverUrl: String? = null,
    @SerialName("image_url")
    val imageUrl: String? = null,
)
