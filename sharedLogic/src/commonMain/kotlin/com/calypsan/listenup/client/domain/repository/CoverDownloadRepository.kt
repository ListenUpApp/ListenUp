package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.core.BookId

/**
 * Repository for downloading book cover images from the server and persisting them locally.
 *
 * Owns its own [kotlinx.coroutines.CoroutineScope] so callers can invoke [queueCoverDownload]
 * from suspend contexts without launching unstructured child coroutines on the caller's scope
 * — the repository is the single structured-concurrency boundary for this work.
 */
internal interface CoverDownloadRepository {
    /**
     * Request that the cover for [bookId] be downloaded.
     *
     * Returns immediately; the download happens on the repository's internal scope.
     * If the cover is already present locally, no work is done. On failure, the error
     * is logged and dropped — the next sync / manual refresh will retry.
     *
     * @param bookId the book whose cover should be fetched.
     */
    fun queueCoverDownload(bookId: BookId)
}
