package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId

/**
 * Contract interface for image API operations.
 *
 * Extracted to enable mocking in tests. Production implementation
 * is [ImageApi], test implementation can be a mock or fake.
 */
interface ImageApiContract {
    /**
     * Download cover image for a book.
     *
     * @param bookId Unique identifier for the book
     * @return Result containing image bytes or error
     */
    suspend fun downloadCover(bookId: BookId): AppResult<ByteArray>

    /**
     * Download profile image for a contributor.
     *
     * @param contributorId Unique identifier for the contributor
     * @return Result containing image bytes or error
     */
    suspend fun downloadContributorImage(contributorId: String): AppResult<ByteArray>

    /**
     * Upload cover image for a book.
     *
     * @param bookId Unique identifier for the book
     * @param imageData Raw image bytes (JPEG, PNG, WebP, or GIF)
     * @param filename Original filename for the image
     * @return Result containing the image URL or error
     */
    suspend fun uploadBookCover(
        bookId: String,
        imageData: ByteArray,
        filename: String,
    ): AppResult<ImageUploadResponse>

    /**
     * Upload profile image for a contributor.
     *
     * @param contributorId Unique identifier for the contributor
     * @param imageData Raw image bytes (JPEG, PNG, WebP, or GIF)
     * @param filename Original filename for the image
     * @return Result containing the image URL or error
     */
    suspend fun uploadContributorImage(
        contributorId: String,
        imageData: ByteArray,
        filename: String,
    ): AppResult<ImageUploadResponse>

    /**
     * Download cover image for a series.
     *
     * @param seriesId Unique identifier for the series
     * @return Result containing image bytes or error
     */
    suspend fun downloadSeriesCover(seriesId: String): AppResult<ByteArray>

    /**
     * Upload cover image for a series.
     *
     * @param seriesId Unique identifier for the series
     * @param imageData Raw image bytes (JPEG, PNG, WebP, or GIF)
     * @param filename Original filename for the image
     * @return Result containing the image URL or error
     */
    suspend fun uploadSeriesCover(
        seriesId: String,
        imageData: ByteArray,
        filename: String,
    ): AppResult<ImageUploadResponse>

    /**
     * Download avatar image for a user.
     *
     * @param userId Unique identifier for the user
     * @return Result containing image bytes or error
     */
    suspend fun downloadUserAvatar(userId: String): AppResult<ByteArray>
}

/**
 * Response from image upload operations.
 */
data class ImageUploadResponse(
    val imageUrl: String,
)
