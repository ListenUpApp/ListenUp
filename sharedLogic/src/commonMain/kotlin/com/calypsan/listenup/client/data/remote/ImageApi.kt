
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
    justification = "Cover/photo/avatar bytes are raw byte streams — no JSON-RPC frame.",
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
}
