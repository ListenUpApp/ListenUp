package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.Failure
import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.client.data.remote.ImageApiContract
import com.calypsan.listenup.client.domain.repository.ImageStorage
import io.github.oshai.kotlinlogging.KotlinLogging
import com.calypsan.listenup.core.Success

private val logger = KotlinLogging.logger {}

private const val BATCH_SIZE = 3

/**
 * Orchestrates downloading and storing book cover images during sync.
 *
 * Responsibilities:
 * - Download covers from backend via ImageApi
 * - Save to local storage via ImageStorage
 * - Extract color palette from covers for instant UI rendering
 * - Handle errors gracefully (missing covers are non-fatal)
 * - Support batch operations for efficient syncing
 *
 * @property imageApi API client for downloading cover images
 * @property imageStorage Local storage for cover images
 */
class ImageDownloader(
    private val imageApi: ImageApiContract,
    private val imageStorage: ImageStorage,
) : ImageDownloaderContract {
    /**
     * Delete a book's cover from local storage.
     *
     * Used when the server's cover has changed and the local cached
     * version needs to be invalidated before downloading the new one.
     */
    override suspend fun deleteCover(bookId: BookId): AppResult<Unit> {
        logger.debug { "Deleting local cover for book ${bookId.value}" }
        return imageStorage.deleteCover(bookId)
    }

    /**
     * Download and save a single book cover.
     *
     * Non-fatal errors (404, network issues) are logged but don't throw.
     * Returns success=true if cover was downloaded and saved successfully.
     *
     * @param bookId Unique identifier for the book
     * @return Result indicating if cover was successfully downloaded and saved
     */
    override suspend fun downloadCover(bookId: BookId): AppResult<Boolean> {
        // Skip if already exists locally
        if (imageStorage.exists(bookId)) {
            logger.info { "Cover already exists locally for book ${bookId.value}" }
            return Success(false)
        }

        logger.info { "Downloading cover for book ${bookId.value}..." }

        // Download from server
        val downloadResult = imageApi.downloadCover(bookId)
        if (downloadResult is Failure) {
            // 404 is expected for books without covers - don't log as error
            logger.info { "Cover not available for book ${bookId.value}: ${downloadResult.message}" }
            return Success(false)
        }

        // Save to local storage
        val imageBytes = (downloadResult as Success).data
        logger.info { "Downloaded ${imageBytes.size} bytes for book ${bookId.value}, saving..." }

        val saveResult = imageStorage.saveCover(bookId, imageBytes)

        if (saveResult is Failure) {
            logger.error { "Failed to save cover for book ${bookId.value}: ${saveResult.message}" }
            return saveResult
        }

        logger.info { "Successfully downloaded and saved cover for book ${bookId.value}" }
        return Success(true)
    }

    /**
     * Download and save a single contributor image.
     *
     * Non-fatal errors (404, network issues) are logged but don't throw.
     * Returns success=true if image was downloaded and saved successfully.
     *
     * @param contributorId Unique identifier for the contributor
     * @return Result indicating if image was successfully downloaded and saved
     */
    override suspend fun downloadContributorImage(contributorId: String): AppResult<Boolean> {
        // Skip if already exists locally
        if (imageStorage.contributorImageExists(contributorId)) {
            logger.info { "Image already exists locally for contributor $contributorId" }
            return Success(false)
        }

        logger.info { "Downloading image for contributor $contributorId..." }

        // Download from server
        val downloadResult = imageApi.downloadContributorImage(contributorId)
        if (downloadResult is Failure) {
            // 404 is expected for contributors without images - don't log as error
            logger.info { "Image not available for contributor $contributorId: ${downloadResult.message}" }
            return Success(false)
        }

        // Save to local storage
        val imageBytes = (downloadResult as Success).data
        logger.info { "Downloaded ${imageBytes.size} bytes for contributor $contributorId, saving..." }

        val saveResult = imageStorage.saveContributorImage(contributorId, imageBytes)

        if (saveResult is Failure) {
            logger.error { "Failed to save image for contributor $contributorId: ${saveResult.message}" }
            return saveResult
        }

        logger.info { "Successfully downloaded and saved image for contributor $contributorId" }
        return Success(true)
    }

    /**
     * Download images for multiple contributors using batch requests.
     *
     * Filters to only contributors missing images locally, then downloads
     * in batches of BATCH_SIZE for efficiency.
     *
     * Continues on individual failures - one failed download doesn't stop the batch.
     * Returns list of contributor IDs that had images successfully downloaded.
     *
     * @param contributorIds List of contributor identifiers to download images for
     * @return Result containing list of contributor IDs that were successfully downloaded
     */
    override suspend fun downloadContributorImages(contributorIds: List<String>): AppResult<List<String>> {
        // Filter to only contributors missing images locally
        val needed = contributorIds.filter { !imageStorage.contributorImageExists(it) }

        if (needed.isEmpty()) {
            logger.debug { "All contributor images already cached" }
            return Success(emptyList())
        }

        logger.debug { "Downloading ${needed.size} contributor images in batches of $BATCH_SIZE" }

        val successfulDownloads =
            buildList {
                // Download in batches
                needed.chunked(BATCH_SIZE).forEach { batch ->
                    when (val result = imageApi.downloadContributorImageBatch(batch)) {
                        is Success -> {
                            result.data.forEach { (contributorId, bytes) ->
                                val saveResult = imageStorage.saveContributorImage(contributorId, bytes)
                                if (saveResult is Success) {
                                    add(contributorId)
                                } else if (saveResult is Failure) {
                                    logger.warn {
                                        "Failed to save image for contributor $contributorId: ${saveResult.message}"
                                    }
                                }
                            }
                            logger.debug { "Downloaded batch of ${result.data.size} contributor images" }
                        }

                        is Failure -> {
                            logger.warn { "Batch download failed: ${result.message}" }
                        }
                    }
                }
            }

        logger.info { "Downloaded ${successfulDownloads.size} images out of ${contributorIds.size} contributors" }
        return Success(successfulDownloads)
    }

    /**
     * Get the local file path for a contributor's image.
     *
     * @param contributorId Unique identifier for the contributor
     * @return Absolute file path where the image is stored, or null if image doesn't exist
     */
    override fun getContributorImagePath(contributorId: String): String? =
        if (imageStorage.contributorImageExists(contributorId)) {
            imageStorage.getContributorImagePath(contributorId)
        } else {
            null
        }

    /**
     * Download and save a single series cover.
     *
     * Non-fatal errors (404, network issues) are logged but don't throw.
     * Returns success=true if cover was downloaded and saved successfully.
     *
     * @param seriesId Unique identifier for the series
     * @return Result indicating if cover was successfully downloaded and saved
     */
    override suspend fun downloadSeriesCover(seriesId: String): AppResult<Boolean> {
        // Skip if already exists locally
        if (imageStorage.seriesCoverExists(seriesId)) {
            logger.info { "Cover already exists locally for series $seriesId" }
            return Success(false)
        }

        logger.info { "Downloading cover for series $seriesId..." }

        // Download from server
        val downloadResult = imageApi.downloadSeriesCover(seriesId)
        if (downloadResult is Failure) {
            // 404 is expected for series without covers - don't log as error
            logger.info { "Cover not available for series $seriesId: ${downloadResult.message}" }
            return Success(false)
        }

        // Save to local storage
        val imageBytes = (downloadResult as Success).data
        logger.info { "Downloaded ${imageBytes.size} bytes for series $seriesId, saving..." }

        val saveResult = imageStorage.saveSeriesCover(seriesId, imageBytes)

        if (saveResult is Failure) {
            logger.error { "Failed to save cover for series $seriesId: ${saveResult.message}" }
            return saveResult
        }

        logger.info { "Successfully downloaded and saved cover for series $seriesId" }
        return Success(true)
    }

    /**
     * Download covers for multiple series in batch.
     *
     * Continues on individual failures - one failed download doesn't stop the batch.
     * Returns list of series IDs that had covers successfully downloaded.
     *
     * @param seriesIds List of series identifiers to download covers for
     * @return Result containing list of series IDs that were successfully downloaded
     */
    override suspend fun downloadSeriesCovers(seriesIds: List<String>): AppResult<List<String>> {
        val successfulDownloads =
            buildList {
                seriesIds.forEach { seriesId ->
                    when (val result = downloadSeriesCover(seriesId)) {
                        is Success -> {
                            if (result.data) {
                                add(seriesId)
                            }
                        }

                        is Failure -> {
                            // Log and continue - non-fatal
                            logger.warn { "Failed to download cover for series $seriesId: ${result.message}" }
                        }
                    }
                }
            }

        logger.info { "Downloaded ${successfulDownloads.size} covers out of ${seriesIds.size} series" }
        return Success(successfulDownloads)
    }

    /**
     * Download and save a user's avatar image.
     *
     * @param userId Unique identifier for the user
     * @param forceRefresh If true, re-downloads even if avatar exists locally
     * @return Result indicating if avatar was successfully downloaded (true) or already existed/unavailable (false)
     */
    override suspend fun downloadUserAvatar(
        userId: String,
        forceRefresh: Boolean,
    ): AppResult<Boolean> {
        // Skip if already exists locally (unless forcing refresh)
        if (!forceRefresh && imageStorage.userAvatarExists(userId)) {
            logger.info { "Avatar already exists locally for user $userId" }
            return Success(false)
        }

        // Delete existing if force refresh
        if (forceRefresh && imageStorage.userAvatarExists(userId)) {
            imageStorage.deleteUserAvatar(userId)
            logger.info { "Deleted old avatar for user $userId before refresh" }
        }

        logger.info { "Downloading avatar for user $userId..." }

        // Download from server
        val downloadResult = imageApi.downloadUserAvatar(userId)
        if (downloadResult is Failure) {
            // 404 is expected for users without custom avatars - don't log as error
            logger.info { "Avatar not available for user $userId: ${downloadResult.message}" }
            return Success(false)
        }

        // Save to local storage
        val imageBytes = (downloadResult as Success).data
        logger.info { "Downloaded ${imageBytes.size} bytes for user $userId, saving..." }

        val saveResult = imageStorage.saveUserAvatar(userId, imageBytes)

        if (saveResult is Failure) {
            logger.error { "Failed to save avatar for user $userId: ${saveResult.message}" }
            return saveResult
        }

        logger.info { "Successfully downloaded and saved avatar for user $userId" }
        return Success(true)
    }

    /**
     * Get the local file path for a user's avatar image.
     *
     * @param userId Unique identifier for the user
     * @return Absolute file path where the avatar is stored, or null if not available
     */
    override fun getUserAvatarPath(userId: String): String? =
        if (imageStorage.userAvatarExists(userId)) {
            imageStorage.getUserAvatarPath(userId)
        } else {
            null
        }

    /**
     * Delete a user's avatar from local storage.
     *
     * @param userId Unique identifier for the user
     * @return Result indicating success or failure
     */
    override suspend fun deleteUserAvatar(userId: String): AppResult<Unit> {
        logger.debug { "Deleting local avatar for user $userId" }
        return imageStorage.deleteUserAvatar(userId)
    }
}
