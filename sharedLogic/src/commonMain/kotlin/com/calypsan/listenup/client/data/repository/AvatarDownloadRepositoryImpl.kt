package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.data.sync.ImageDownloaderContract
import com.calypsan.listenup.client.domain.repository.AvatarDownloadRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * [AvatarDownloadRepository] implementation backed by [ImageDownloaderContract].
 *
 * Mirrors [CoverDownloadRepositoryImpl]. No `touchUpdatedAt` post-fetch — avatars paint
 * from a stable file path and do not require a Room invalidation signal for UI refresh.
 *
 * @property imageDownloader underlying downloader (platform-specific through Koin).
 * @property scope the repository's structured-concurrency scope. Child jobs launched
 *   here are bounded by the scope's lifecycle — typically the application scope.
 */
class AvatarDownloadRepositoryImpl(
    private val imageDownloader: ImageDownloaderContract,
    private val scope: CoroutineScope,
) : AvatarDownloadRepository {
    override fun queueAvatarDownload(userId: String) {
        scope.launch {
            try {
                imageDownloader.downloadUserAvatar(userId, forceRefresh = false)
                logger.debug { "Queued avatar download for user $userId" }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Failed to download avatar for user $userId" }
            }
        }
    }

    override fun queueAvatarForceRefresh(userId: String) {
        scope.launch {
            try {
                imageDownloader.downloadUserAvatar(userId, forceRefresh = true)
                logger.debug { "Force-refreshed avatar for user $userId" }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Failed to force-refresh avatar for user $userId" }
            }
        }
    }

    override suspend fun deleteAvatar(userId: String) {
        try {
            imageDownloader.deleteUserAvatar(userId)
            logger.debug { "Deleted avatar for user $userId" }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to delete avatar for user $userId" }
        }
    }
}
