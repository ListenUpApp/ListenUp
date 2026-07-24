package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.remote.ImageApiContract
import com.calypsan.listenup.client.domain.repository.ImageStorage
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val logger = KotlinLogging.logger {}

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
 * @property bookDao maintains [com.calypsan.listenup.client.data.local.db.BookEntity.coverDownloadedAt] —
 *   the cover-presence marker mapping reads instead of stat-ing the filesystem. This is the single
 *   choke point every cover download and deletion funnels through, so it is the sole place the
 *   marker is written.
 */
internal class ImageDownloader(
    private val imageApi: ImageApiContract,
    private val imageStorage: ImageStorage,
    private val bookDao: BookDao,
) : ImageDownloaderContract {
    // Per-userId serialization so a post-scan fan-out of concurrent avatar requests
    // collapses to a single network fetch + save instead of stampeding the server
    // (and deadlocking the iOS AppResult suspend bridge).
    // Both collections grow unbounded but are bounded by distinct users seen this session —
    // acceptable for the small self-hosted userbase.
    private val avatarDownloadMutexes = mutableMapOf<String, Mutex>()
    private val avatarDownloadMutexesGuard = Mutex()

    // Session negative cache: a 404 means the user has no image avatar, so repeated
    // triggers must not keep re-hitting the server. Distinct users run concurrently on the
    // multi-threaded appScope, so every access is guarded (the per-user mutex only serializes
    // the same userId).
    private val avatarsKnownMissing = mutableSetOf<String>()

    private suspend fun avatarMutexFor(userId: String): Mutex =
        avatarDownloadMutexesGuard.withLock { avatarDownloadMutexes.getOrPut(userId) { Mutex() } }

    private suspend fun markAvatarMissing(userId: String) =
        avatarDownloadMutexesGuard.withLock { avatarsKnownMissing.add(userId) }

    private suspend fun clearAvatarMissing(userId: String) =
        avatarDownloadMutexesGuard.withLock { avatarsKnownMissing.remove(userId) }

    private suspend fun isAvatarKnownMissing(userId: String): Boolean =
        avatarDownloadMutexesGuard.withLock { avatarsKnownMissing.contains(userId) }

    /**
     * Delete a book's cover from local storage.
     *
     * Used when the server's cover has changed and the local cached
     * version needs to be invalidated before downloading the new one.
     */
    override suspend fun deleteCover(bookId: BookId): AppResult<Unit> {
        logger.debug { "Deleting local cover for book ${bookId.value}" }
        val result = imageStorage.deleteCover(bookId)
        if (result is AppResult.Success) {
            bookDao.clearCoverDownloaded(bookId)
        }
        return result
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
        // Skip if already exists locally. This is also how a v42→v43 upgrader's pre-existing
        // cover files (written before the marker column existed) get marked ahead of the
        // startup reconciler running.
        if (imageStorage.exists(bookId)) {
            logger.info { "Cover already exists locally for book ${bookId.value}" }
            bookDao.markCoverDownloaded(bookId, Timestamp.now())
            return AppResult.Success(false)
        }

        logger.info { "Downloading cover for book ${bookId.value}..." }

        // Download from server
        val downloadResult = imageApi.downloadCover(bookId)
        if (downloadResult is AppResult.Failure) {
            // 404 is expected for books without covers - don't log as error
            logger.info { "Cover not available for book ${bookId.value}: ${downloadResult.message}" }
            return AppResult.Success(false)
        }

        // Save to local storage
        val imageBytes = (downloadResult as AppResult.Success).data
        logger.info { "Downloaded ${imageBytes.size} bytes for book ${bookId.value}, saving..." }

        val saveResult = imageStorage.saveCover(bookId, imageBytes)

        if (saveResult is AppResult.Failure) {
            logger.error { "Failed to save cover for book ${bookId.value}: ${saveResult.message}" }
            return saveResult
        }

        bookDao.markCoverDownloaded(bookId, Timestamp.now())
        logger.info { "Successfully downloaded and saved cover for book ${bookId.value}" }
        return AppResult.Success(true)
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
            return AppResult.Success(false)
        }

        logger.info { "Downloading image for contributor $contributorId..." }

        // Download from server
        val downloadResult = imageApi.downloadContributorImage(contributorId)
        if (downloadResult is AppResult.Failure) {
            // 404 is expected for contributors without images - don't log as error
            logger.info { "Image not available for contributor $contributorId: ${downloadResult.message}" }
            return AppResult.Success(false)
        }

        // Save to local storage
        val imageBytes = (downloadResult as AppResult.Success).data
        logger.info { "Downloaded ${imageBytes.size} bytes for contributor $contributorId, saving..." }

        val saveResult = imageStorage.saveContributorImage(contributorId, imageBytes)

        if (saveResult is AppResult.Failure) {
            logger.error { "Failed to save image for contributor $contributorId: ${saveResult.message}" }
            return saveResult
        }

        logger.info { "Successfully downloaded and saved image for contributor $contributorId" }
        return AppResult.Success(true)
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
            return AppResult.Success(false)
        }

        logger.info { "Downloading cover for series $seriesId..." }

        // Download from server
        val downloadResult = imageApi.downloadSeriesCover(seriesId)
        if (downloadResult is AppResult.Failure) {
            // 404 is expected for series without covers - don't log as error
            logger.info { "Cover not available for series $seriesId: ${downloadResult.message}" }
            return AppResult.Success(false)
        }

        // Save to local storage
        val imageBytes = (downloadResult as AppResult.Success).data
        logger.info { "Downloaded ${imageBytes.size} bytes for series $seriesId, saving..." }

        val saveResult = imageStorage.saveSeriesCover(seriesId, imageBytes)

        if (saveResult is AppResult.Failure) {
            logger.error { "Failed to save cover for series $seriesId: ${saveResult.message}" }
            return saveResult
        }

        logger.info { "Successfully downloaded and saved cover for series $seriesId" }
        return AppResult.Success(true)
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
                        is AppResult.Success -> {
                            if (result.data) {
                                add(seriesId)
                            }
                        }

                        is AppResult.Failure -> {
                            // Log and continue - non-fatal
                            logger.warn { "Failed to download cover for series $seriesId: ${result.message}" }
                        }
                    }
                }
            }

        logger.info { "Downloaded ${successfulDownloads.size} covers out of ${seriesIds.size} series" }
        return AppResult.Success(successfulDownloads)
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
    ): AppResult<Boolean> =
        avatarMutexFor(userId).withLock {
            // A forced refresh clears any prior negative-cache verdict and the stale file.
            if (forceRefresh) {
                clearAvatarMissing(userId)
                if (imageStorage.userAvatarExists(userId)) {
                    // Best-effort cleanup before re-download; the fresh file overwrites a failed delete anyway.
                    val _ = imageStorage.deleteUserAvatar(userId)
                    logger.info { "Deleted old avatar for user $userId before refresh" }
                }
            }

            // Re-check inside the lock: a coalesced earlier caller may have just saved the file.
            if (!forceRefresh && imageStorage.userAvatarExists(userId)) {
                logger.info { "Avatar already exists locally for user $userId" }
                return@withLock AppResult.Success(false)
            }

            // Negative cache: the server already told us this user has no avatar this session.
            if (!forceRefresh && isAvatarKnownMissing(userId)) {
                logger.info { "Avatar known missing for user $userId, skipping download" }
                return@withLock AppResult.Success(false)
            }

            logger.info { "Downloading avatar for user $userId..." }

            // Download from server
            val downloadResult = imageApi.downloadUserAvatar(userId)
            if (downloadResult is AppResult.Failure) {
                // 404 is expected for users without custom avatars - don't log as error
                markAvatarMissing(userId)
                logger.info { "Avatar not available for user $userId: ${downloadResult.message}" }
                return@withLock AppResult.Success(false)
            }

            // Save to local storage
            val imageBytes = (downloadResult as AppResult.Success).data
            logger.info { "Downloaded ${imageBytes.size} bytes for user $userId, saving..." }

            val saveResult = imageStorage.saveUserAvatar(userId, imageBytes)

            if (saveResult is AppResult.Failure) {
                logger.error { "Failed to save avatar for user $userId: ${saveResult.message}" }
                return@withLock saveResult
            }

            clearAvatarMissing(userId)
            logger.info { "Successfully downloaded and saved avatar for user $userId" }
            AppResult.Success(true)
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
