package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.data.sync.ImageDownloaderContract
import com.calypsan.listenup.client.domain.repository.CoverDownloadRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * [CoverDownloadRepository] implementation backed by [ImageDownloaderContract] for the fetch.
 *
 * [ImageDownloaderContract.downloadCover] itself maintains the `coverDownloadedAt`
 * cover-presence marker on a successful download, so Room's invalidation tracker wakes
 * observers without this repository needing a separate post-fetch touch.
 *
 * @property imageDownloader underlying downloader (platform-specific through Koin).
 * @property scope the repository's structured-concurrency scope. Child jobs launched
 *   here are bounded by the scope's lifecycle — typically the application scope.
 */
internal class CoverDownloadRepositoryImpl(
    private val imageDownloader: ImageDownloaderContract,
    private val scope: CoroutineScope,
) : CoverDownloadRepository {
    override fun queueCoverDownload(bookId: BookId) {
        scope.launch {
            try {
                imageDownloader.downloadCover(bookId)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Failed to download cover for book ${bookId.value}" }
            }
        }
    }
}
