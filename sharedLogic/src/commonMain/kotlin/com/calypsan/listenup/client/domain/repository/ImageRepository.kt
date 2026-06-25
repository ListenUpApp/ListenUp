@file:MustUseReturnValues

package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.api.result.AppResult

/**
 * Domain repository for persistent image operations.
 *
 * Covers download, upload, path queries, and main-cover management for books,
 * series, contributors, and user avatars. Staging-lifecycle operations live in
 * [ImageStagingRepository].
 *
 * This abstracts:
 * - ImageDownloaderContract (download operations)
 * - ImageStorage (local file operations)
 * - ImageApiContract (upload operations)
 *
 * Into a single domain-level contract.
 */
interface ImageRepository {
    // ========== Book Cover Operations ==========

    /**
     * Delete a book's cover from local storage.
     *
     * Used when the server's cover has changed and the local cached
     * version needs to be invalidated before downloading the new one.
     *
     * @param bookId Unique identifier for the book
     * @return Result indicating success or failure
     */
    suspend fun deleteBookCover(bookId: BookId): AppResult<Unit>

    /**
     * Download and save a single book cover from the server.
     *
     * @param bookId Unique identifier for the book
     * @return Result indicating if cover was downloaded (true) or already existed/unavailable (false)
     */
    suspend fun downloadBookCover(bookId: BookId): AppResult<Boolean>

    /**
     * Best-effort: ensure a book's cover is cached on local disk for offline use.
     *
     * Returns immediately; the download runs on the repository's app scope, so it survives the
     * caller leaving composition. No-op if the cover already exists locally. Failures are logged
     * and dropped — the next view or sync retries. Used by cover components to lazily persist a
     * cover the first time it is streamed from the server, so it stays available offline
     * independent of the image loader's evictable cache.
     *
     * @param bookId Unique identifier for the book
     */
    fun ensureBookCoverCached(bookId: BookId)

    /**
     * Best-effort: ensure a contributor's image is cached on local disk for offline use.
     *
     * Fire-and-forget analogue of [ensureBookCoverCached] for contributor photos. Returns
     * immediately; the download runs on the repository's app scope and is a no-op if the image
     * already exists locally. Used by contributor image components to lazily persist a photo the
     * first time it is streamed from the server.
     *
     * @param contributorId Unique identifier for the contributor
     */
    fun ensureContributorImageCached(contributorId: String)

    /**
     * Best-effort: ensure a user's avatar is cached on local disk for offline use.
     *
     * Fire-and-forget analogue of [ensureBookCoverCached] for user avatars. Returns immediately;
     * the download runs on the repository's app scope and is a no-op if the avatar already exists
     * locally. Lets avatars shown in feeds/leaderboards persist (and render the real photo rather
     * than initials) without first visiting the user's profile.
     *
     * @param userId Unique identifier for the user
     */
    fun ensureUserAvatarCached(userId: String)

    /**
     * Upload book cover to the server.
     *
     * @param bookId Unique identifier for the book
     * @param imageData Raw image bytes
     * @param filename Original filename
     * @return Result containing the server image URL or error
     */
    suspend fun uploadBookCover(
        bookId: String,
        imageData: ByteArray,
        filename: String,
    ): AppResult<String>

    // ========== Series Cover Operations ==========

    /**
     * Upload series cover to the server.
     *
     * @param seriesId Unique identifier for the series
     * @param imageData Raw image bytes
     * @param filename Original filename
     * @return Result containing the server image URL or error
     */
    suspend fun uploadSeriesCover(
        seriesId: String,
        imageData: ByteArray,
        filename: String,
    ): AppResult<String>

    // ========== Contributor Image Operations ==========

    /**
     * Download contributor image from the server and save locally.
     *
     * @param contributorId Unique identifier for the contributor
     * @return Result containing the image bytes or error
     */
    suspend fun downloadContributorImage(contributorId: String): AppResult<ByteArray>

    /**
     * Save contributor image to local storage.
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
     * Get the local file path for a contributor's image.
     *
     * @param contributorId Unique identifier for the contributor
     * @return Absolute file path where the image is stored
     */
    fun getContributorImagePath(contributorId: String): String

    /**
     * Upload contributor image to the server.
     *
     * @param contributorId Unique identifier for the contributor
     * @param imageData Raw image bytes
     * @param filename Original filename
     * @return Result containing the server image URL or error
     */
    suspend fun uploadContributorImage(
        contributorId: String,
        imageData: ByteArray,
        filename: String,
    ): AppResult<String>

    // ========== Contributor Image Path Operations ==========

    /**
     * Check if a contributor's image exists locally.
     *
     * @param contributorId Unique identifier for the contributor
     * @return true if image exists on disk, false otherwise
     */
    fun contributorImageExists(contributorId: String): Boolean

    // ========== Book Cover Path Operations ==========

    /**
     * Check if a book's cover exists locally.
     *
     * @param bookId Unique identifier for the book
     * @return true if cover exists on disk, false otherwise
     */
    fun bookCoverExists(bookId: BookId): Boolean

    /**
     * Get the local file path for a book's cover.
     *
     * @param bookId Unique identifier for the book
     * @return Absolute file path where the cover is stored
     */
    fun getBookCoverPath(bookId: BookId): String

    // ========== Series Cover Path Operations ==========

    /**
     * Check if a series cover exists locally.
     *
     * @param seriesId Unique identifier for the series
     * @return true if cover exists on disk, false otherwise
     */
    fun seriesCoverExists(seriesId: String): Boolean

    /**
     * Get the local file path for a series cover.
     *
     * @param seriesId Unique identifier for the series
     * @return Absolute file path where the cover is stored
     */
    fun getSeriesCoverPath(seriesId: String): String

    // ========== User Avatar Operations ==========

    /**
     * Check if a user's avatar exists locally.
     *
     * @param userId Unique identifier for the user
     * @return true if avatar exists on disk, false otherwise
     */
    fun userAvatarExists(userId: String): Boolean

    /**
     * Get the local file path for a user's avatar.
     *
     * @param userId Unique identifier for the user
     * @return Absolute file path where the avatar is stored
     */
    fun getUserAvatarPath(userId: String): String

    /**
     * Download and save a user's avatar from the server.
     *
     * @param userId Unique identifier for the user
     * @param forceRefresh If true, re-downloads even if avatar exists locally
     * @return Result indicating if avatar was downloaded (true) or already existed/unavailable (false)
     */
    suspend fun downloadUserAvatar(
        userId: String,
        forceRefresh: Boolean = false,
    ): AppResult<Boolean>
}
