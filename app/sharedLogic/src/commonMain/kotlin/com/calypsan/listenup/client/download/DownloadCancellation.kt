package com.calypsan.listenup.client.download

import com.calypsan.listenup.api.result.onFailure
import com.calypsan.listenup.client.domain.model.DownloadStatus
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.files.SystemFileSystem

private val logger = KotlinLogging.logger {}

/**
 * Persists the outcome of a cancelled download: marks it paused, and removes the partial `.tmp`
 * if the row has already been moved to CANCELLED.
 *
 * Extracted from [DownloadWorker]'s cancellation catch so it can be exercised without WorkManager.
 *
 * Runs under [NonCancellable] because it is invoked from a coroutine that is *already being
 * cancelled* (the worker's cancellation catch). Without it the first suspending write
 * ([DownloadRepository.markPaused]) aborts at its suspension point and the pause — plus any temp
 * cleanup — is silently lost.
 */
internal suspend fun persistDownloadCancellation(
    repository: DownloadRepository,
    fileManager: DownloadFileManager,
    audioFileId: String,
    bookId: String,
    filename: String,
) = withContext(NonCancellable) {
    repository
        .markPaused(audioFileId)
        .onFailure { logger.warn { "Failed to persist paused state on cancel: $audioFileId" } }
    deleteTempIfCancelled(repository, fileManager, audioFileId, bookId, filename)
}

// If the row was explicitly cancelled by the user (state = CANCELLED), delete the partial .tmp so it
// does not linger. Network-paused downloads (state = PAUSED after markPaused) keep their .tmp for
// Range-resume. cancelForBook() runs after WorkManager.await(), so CANCELLED may not yet be written
// here; the startup sweep in resumeIncompleteDownloads is the durable catch-all.
private suspend fun deleteTempIfCancelled(
    repository: DownloadRepository,
    fileManager: DownloadFileManager,
    audioFileId: String,
    bookId: String,
    filename: String,
) {
    val rowState = repository.getStateForAudioFile(audioFileId)
    if (rowState != DownloadStatus.CANCELLED) return
    val tempPath = fileManager.getAudioFilePath(bookId, audioFileId, filename, isTemp = true)
    if (!SystemFileSystem.exists(tempPath)) return
    try {
        SystemFileSystem.delete(tempPath)
        logger.info { "Deleted orphaned .tmp for cancelled download: $audioFileId" }
    } catch (deleteEx: Exception) {
        logger.warn(deleteEx) { "Failed to delete .tmp for cancelled download: $audioFileId" }
    }
}
