package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.client.data.remote.ImageApiContract
import com.calypsan.listenup.client.data.sync.ImageDownloaderContract
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.ImageStagingRepository
import com.calypsan.listenup.client.domain.repository.ImageStorage
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * Implementation of ImageRepository that delegates to data layer components.
 *
 * This adapter bridges the domain ImageRepository interface to the existing
 * data layer implementations:
 * - ImageDownloaderContract for download operations
 * - ImageStorage for local file operations
 * - ImageApiContract for upload operations
 */
internal class ImageRepositoryImpl(
    private val imageDownloader: ImageDownloaderContract,
    private val imageStorage: ImageStorage,
    private val imageApi: ImageApiContract,
    private val appScope: CoroutineScope,
) : ImageRepository,
    ImageStagingRepository {
    // ========== Book Cover Operations ==========

    override suspend fun deleteBookCover(bookId: BookId): AppResult<Unit> = imageDownloader.deleteCover(bookId)

    override suspend fun downloadBookCover(bookId: BookId): AppResult<Boolean> = imageDownloader.downloadCover(bookId)

    override fun ensureBookCoverCached(bookId: BookId) {
        // Fire-and-forget cache warm; a failed background fetch is retried on next access.
        appScope.launch { val _ = downloadBookCover(bookId) }
    }

    override fun ensureContributorImageCached(contributorId: String) {
        appScope.launch { imageDownloader.downloadContributorImage(contributorId) }
    }

    override fun ensureUserAvatarCached(userId: String) {
        appScope.launch { imageDownloader.downloadUserAvatar(userId, forceRefresh = false) }
    }

    override suspend fun saveBookCoverStaging(
        bookId: BookId,
        imageData: ByteArray,
    ): AppResult<Unit> = imageStorage.saveCoverStaging(bookId, imageData)

    override fun getBookCoverStagingPath(bookId: BookId): String = imageStorage.getCoverStagingPath(bookId)

    override suspend fun deleteBookCoverStaging(bookId: BookId): AppResult<Unit> =
        imageStorage.deleteCoverStaging(bookId)

    override suspend fun commitBookCoverStaging(bookId: BookId): AppResult<Unit> =
        imageStorage.commitCoverStaging(bookId)

    override suspend fun uploadBookCover(
        bookId: String,
        imageData: ByteArray,
        filename: String,
    ): AppResult<String> = imageApi.uploadBookCover(bookId, imageData, filename).map { it.imageUrl }

    // ========== Series Cover Operations ==========

    override suspend fun saveSeriesCoverStaging(
        seriesId: String,
        imageData: ByteArray,
    ): AppResult<Unit> = imageStorage.saveSeriesCoverStaging(seriesId, imageData)

    override fun getSeriesCoverStagingPath(seriesId: String): String = imageStorage.getSeriesCoverStagingPath(seriesId)

    override suspend fun deleteSeriesCoverStaging(seriesId: String): AppResult<Unit> =
        imageStorage.deleteSeriesCoverStaging(seriesId)

    override suspend fun commitSeriesCoverStaging(seriesId: String): AppResult<Unit> =
        imageStorage.commitSeriesCoverStaging(seriesId)

    override suspend fun uploadSeriesCover(
        seriesId: String,
        imageData: ByteArray,
        filename: String,
    ): AppResult<String> = imageApi.uploadSeriesCover(seriesId, imageData, filename).map { it.imageUrl }

    // ========== Contributor Image Operations ==========

    override suspend fun downloadContributorImage(contributorId: String): AppResult<ByteArray> =
        imageApi.downloadContributorImage(contributorId)

    override suspend fun saveContributorImage(
        contributorId: String,
        imageData: ByteArray,
    ): AppResult<Unit> = imageStorage.saveContributorImage(contributorId, imageData)

    override fun getContributorImagePath(contributorId: String): String =
        imageStorage.getContributorImagePath(contributorId)

    override suspend fun uploadContributorImage(
        contributorId: String,
        imageData: ByteArray,
        filename: String,
    ): AppResult<String> = imageApi.uploadContributorImage(contributorId, imageData, filename).map { it.imageUrl }

    override suspend fun saveContributorImageStaging(
        contributorId: String,
        imageData: ByteArray,
    ): AppResult<Unit> = imageStorage.saveContributorImageStaging(contributorId, imageData)

    override fun getContributorImageStagingPath(contributorId: String): String =
        imageStorage.getContributorImageStagingPath(contributorId)

    override suspend fun deleteContributorImageStaging(contributorId: String): AppResult<Unit> =
        imageStorage.deleteContributorImageStaging(contributorId)

    override suspend fun commitContributorImageStaging(contributorId: String): AppResult<Unit> =
        imageStorage.commitContributorImageStaging(contributorId)

    // ========== Contributor Image Path Operations ==========

    override fun contributorImageExists(contributorId: String): Boolean =
        imageStorage.contributorImageExists(contributorId)

    // ========== Book Cover Path Operations ==========

    override fun bookCoverExists(bookId: BookId): Boolean = imageStorage.exists(bookId)

    override fun getBookCoverPath(bookId: BookId): String = imageStorage.getCoverPath(bookId)

    // ========== Series Cover Path Operations ==========

    override fun seriesCoverExists(seriesId: String): Boolean = imageStorage.seriesCoverExists(seriesId)

    override fun getSeriesCoverPath(seriesId: String): String = imageStorage.getSeriesCoverPath(seriesId)

    // ========== User Avatar Operations ==========

    override fun userAvatarExists(userId: String): Boolean = imageStorage.userAvatarExists(userId)

    override fun getUserAvatarPath(userId: String): String = imageStorage.getUserAvatarPath(userId)

    override suspend fun downloadUserAvatar(
        userId: String,
        forceRefresh: Boolean,
    ): AppResult<Boolean> = imageDownloader.downloadUserAvatar(userId, forceRefresh)

    // ========== Fire-and-forget staging cleanup ==========

    override fun requestBookCoverStagingCleanup(bookId: BookId) {
        appScope.launch {
            when (val r = deleteBookCoverStaging(bookId)) {
                is AppResult.Failure -> {
                    logger.warn { "Staging cleanup failed for book ${bookId.value}: ${r.message}" }
                }

                is AppResult.Success -> {}
            }
        }
    }

    override fun requestSeriesCoverStagingCleanup(seriesId: String) {
        appScope.launch {
            when (val r = deleteSeriesCoverStaging(seriesId)) {
                is AppResult.Failure -> {
                    logger.warn { "Staging cleanup failed for series $seriesId: ${r.message}" }
                }

                is AppResult.Success -> {}
            }
        }
    }

    override fun requestContributorImageStagingCleanup(contributorId: String) {
        appScope.launch {
            when (val r = deleteContributorImageStaging(contributorId)) {
                is AppResult.Failure -> {
                    logger.warn { "Staging cleanup failed for contributor $contributorId: ${r.message}" }
                }

                is AppResult.Success -> {}
            }
        }
    }
}
