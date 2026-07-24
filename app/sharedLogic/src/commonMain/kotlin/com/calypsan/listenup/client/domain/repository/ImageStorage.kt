@file:MustUseReturnValues

package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.api.result.AppResult

/**
 * Platform-agnostic interface for storing and retrieving images locally.
 * Implementations handle platform-specific file system operations.
 *
 * Supports:
 * - Book cover images (stored in covers/)
 * - Contributor profile images (stored in contributors/)
 * - Series cover images (stored in covers/series/)
 */
@Suppress("TooManyFunctions")
interface ImageStorage {
    // ========== Book Cover Methods ==========

    /**
     * Save cover image data to local storage.
     *
     * @param bookId Unique identifier for the book
     * @param imageData Raw image bytes (JPEG format from server)
     * @return Result indicating success or failure
     */
    suspend fun saveCover(
        bookId: BookId,
        imageData: ByteArray,
    ): AppResult<Unit>

    /**
     * Get the local file path for a book's cover image.
     * Does not verify if the file exists.
     *
     * @param bookId Unique identifier for the book
     * @return Absolute file path where the cover is/would be stored
     */
    fun getCoverPath(bookId: BookId): String

    /**
     * Check if a cover image exists locally.
     *
     * @param bookId Unique identifier for the book
     * @return true if cover exists on disk, false otherwise
     */
    fun exists(bookId: BookId): Boolean

    /**
     * List the book ids that have a cover image file on disk — one directory listing,
     * no per-book stats. Staging files and non-cover entries are excluded. Used by the
     * startup cover-presence reconciler to heal the persisted cover marker.
     */
    fun listCoverBookIds(): Set<BookId>

    /**
     * Delete a cover image from local storage.
     * No-op if the cover doesn't exist.
     *
     * @param bookId Unique identifier for the book
     * @return Result indicating success or failure
     */
    suspend fun deleteCover(bookId: BookId): AppResult<Unit>

    // ========== Book Cover Staging Methods ==========
    // Used for edit screens to preview changes before committing

    /**
     * Save cover image to a staging location for preview.
     * Does not affect the main cover file until [commitCoverStaging] is called.
     *
     * @param bookId Unique identifier for the book
     * @param imageData Raw image bytes
     * @return Result indicating success or failure
     */
    suspend fun saveCoverStaging(
        bookId: BookId,
        imageData: ByteArray,
    ): AppResult<Unit>

    /**
     * Get the local file path for a book's staging cover image.
     *
     * @param bookId Unique identifier for the book
     * @return Absolute file path where the staging cover is/would be stored
     */
    fun getCoverStagingPath(bookId: BookId): String

    /**
     * Move staging cover to the main cover location.
     * Call this when the user saves their changes.
     *
     * @param bookId Unique identifier for the book
     * @return Result indicating success or failure
     */
    suspend fun commitCoverStaging(bookId: BookId): AppResult<Unit>

    /**
     * Delete staging cover file.
     * Call this when the user cancels their changes.
     *
     * @param bookId Unique identifier for the book
     * @return Result indicating success or failure
     */
    suspend fun deleteCoverStaging(bookId: BookId): AppResult<Unit>

    /**
     * Clear all cover images from local storage.
     * Used for cleanup operations (e.g., logout, cache clear).
     *
     * @return Result with count of deleted files
     */
    suspend fun clearAll(): AppResult<Int>

    // ========== Contributor Image Methods ==========

    /**
     * Save contributor profile image to local storage.
     *
     * @param contributorId Unique identifier for the contributor
     * @param imageData Raw image bytes
     * @return Result indicating success or failure
     */
    suspend fun saveContributorImage(
        contributorId: String,
        imageData: ByteArray,
    ): AppResult<Unit>

    /**
     * Get the local file path for a contributor's profile image.
     * Does not verify if the file exists.
     *
     * @param contributorId Unique identifier for the contributor
     * @return Absolute file path where the image is/would be stored
     */
    fun getContributorImagePath(contributorId: String): String

    /**
     * Check if a contributor image exists locally.
     *
     * @param contributorId Unique identifier for the contributor
     * @return true if image exists on disk, false otherwise
     */
    fun contributorImageExists(contributorId: String): Boolean

    /**
     * Delete a contributor image from local storage.
     * No-op if the image doesn't exist.
     *
     * @param contributorId Unique identifier for the contributor
     * @return Result indicating success or failure
     */
    suspend fun deleteContributorImage(contributorId: String): AppResult<Unit>

    // ========== Series Cover Methods ==========

    /**
     * Save series cover image to local storage.
     *
     * @param seriesId Unique identifier for the series
     * @param imageData Raw image bytes
     * @return Result indicating success or failure
     */
    suspend fun saveSeriesCover(
        seriesId: String,
        imageData: ByteArray,
    ): AppResult<Unit>

    /**
     * Get the local file path for a series's cover image.
     * Does not verify if the file exists.
     *
     * @param seriesId Unique identifier for the series
     * @return Absolute file path where the image is/would be stored
     */
    fun getSeriesCoverPath(seriesId: String): String

    /**
     * Check if a series cover image exists locally.
     *
     * @param seriesId Unique identifier for the series
     * @return true if image exists on disk, false otherwise
     */
    fun seriesCoverExists(seriesId: String): Boolean

    /**
     * Delete a series cover image from local storage.
     * No-op if the image doesn't exist.
     *
     * @param seriesId Unique identifier for the series
     * @return Result indicating success or failure
     */
    suspend fun deleteSeriesCover(seriesId: String): AppResult<Unit>

    // ========== User Avatar Methods ==========

    /**
     * Save user avatar image to local storage.
     *
     * @param userId Unique identifier for the user
     * @param imageData Raw image bytes
     * @return Result indicating success or failure
     */
    suspend fun saveUserAvatar(
        userId: String,
        imageData: ByteArray,
    ): AppResult<Unit>

    /**
     * Get the local file path for a user's avatar image.
     * Does not verify if the file exists.
     *
     * @param userId Unique identifier for the user
     * @return Absolute file path where the avatar is/would be stored
     */
    fun getUserAvatarPath(userId: String): String

    /**
     * Check if a user avatar image exists locally.
     *
     * @param userId Unique identifier for the user
     * @return true if avatar exists on disk, false otherwise
     */
    fun userAvatarExists(userId: String): Boolean

    /**
     * Delete a user avatar image from local storage.
     * No-op if the avatar doesn't exist.
     *
     * @param userId Unique identifier for the user
     * @return Result indicating success or failure
     */
    suspend fun deleteUserAvatar(userId: String): AppResult<Unit>

    // ========== Series Cover Staging Methods ==========

    /**
     * Save series cover image to a staging location for preview.
     * Does not affect the main cover file until [commitSeriesCoverStaging] is called.
     *
     * @param seriesId Unique identifier for the series
     * @param imageData Raw image bytes
     * @return Result indicating success or failure
     */
    suspend fun saveSeriesCoverStaging(
        seriesId: String,
        imageData: ByteArray,
    ): AppResult<Unit>

    /**
     * Get the local file path for a series's staging cover image.
     *
     * @param seriesId Unique identifier for the series
     * @return Absolute file path where the staging cover is/would be stored
     */
    fun getSeriesCoverStagingPath(seriesId: String): String

    /**
     * Move staging cover to the main cover location.
     * Call this when the user saves their changes.
     *
     * @param seriesId Unique identifier for the series
     * @return Result indicating success or failure
     */
    suspend fun commitSeriesCoverStaging(seriesId: String): AppResult<Unit>

    /**
     * Delete staging cover file.
     * Call this when the user cancels their changes.
     *
     * @param seriesId Unique identifier for the series
     * @return Result indicating success or failure
     */
    suspend fun deleteSeriesCoverStaging(seriesId: String): AppResult<Unit>

    // ========== Contributor Image Staging Methods ==========

    /**
     * Save contributor image to a staging location for preview.
     * Does not affect the main image file until [commitContributorImageStaging] is called.
     *
     * @param contributorId Unique identifier for the contributor
     * @param imageData Raw image bytes
     * @return Result indicating success or failure
     */
    suspend fun saveContributorImageStaging(
        contributorId: String,
        imageData: ByteArray,
    ): AppResult<Unit>

    /**
     * Get the local file path for a contributor's staging image.
     *
     * @param contributorId Unique identifier for the contributor
     * @return Absolute file path where the staging image is/would be stored
     */
    fun getContributorImageStagingPath(contributorId: String): String

    /**
     * Move staging image to the main contributor image location.
     * Call this when the user saves their changes.
     *
     * @param contributorId Unique identifier for the contributor
     * @return Result indicating success or failure
     */
    suspend fun commitContributorImageStaging(contributorId: String): AppResult<Unit>

    /**
     * Delete staging image file.
     * Call this when the user cancels their changes.
     *
     * @param contributorId Unique identifier for the contributor
     * @return Result indicating success or failure
     */
    suspend fun deleteContributorImageStaging(contributorId: String): AppResult<Unit>
}
