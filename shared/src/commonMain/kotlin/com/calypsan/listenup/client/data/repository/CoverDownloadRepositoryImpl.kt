package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.Success
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.sync.ImageDownloaderContract
import com.calypsan.listenup.client.domain.repository.CoverDownloadRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * [CoverDownloadRepository] implementation backed by [ImageDownloaderContract] for the
 * fetch and [BookDao] for a post-fetch `updatedAt` touch (triggers UI refresh on screens
 * observing the book).
 *
 * @property imageDownloader underlying downloader (platform-specific through Koin).
 * @property bookDao used to touch the book row after a successful download so
 *   Room's invalidation tracker wakes observers.
 * @property scope the repository's structured-concurrency scope. Child jobs launched
 *   here are bounded by the scope's lifecycle — typically the application scope.
 */
class CoverDownloadRepositoryImpl(
    private val imageDownloader: ImageDownloaderContract,
    private val bookDao: BookDao,
    private val scope: CoroutineScope,
) : CoverDownloadRepository {
    override fun queueCoverDownload(bookId: BookId) {
        scope.launch {
            try {
                val result = imageDownloader.downloadCover(bookId)
                if (result is Success && result.data) {
                    try {
                        bookDao.touchUpdatedAt(bookId, Timestamp.now())
                        logger.debug { "Touched book ${bookId.value} to trigger UI refresh" }
                    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to touch updatedAt for book ${bookId.value}" }
                    }
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Failed to download cover for book ${bookId.value}" }
            }
        }
    }
}
