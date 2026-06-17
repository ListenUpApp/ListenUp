package com.calypsan.listenup.client.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.error.DownloadError
import com.calypsan.listenup.core.error.ErrorBus
import com.calypsan.listenup.client.data.local.db.DownloadState
import com.calypsan.listenup.client.data.remote.PlaybackRpcFactory
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.io.files.SystemFileSystem

private val logger = KotlinLogging.logger {}

/**
 * WorkManager worker that downloads a single audio file.
 *
 * Features:
 * - Signed URL resolution via [PlaybackService.prepare]
 * - Resume support (Range headers)
 * - Progress updates
 * - Cancellation handling
 * - Automatic retry (up to 3 times)
 */
class DownloadWorker(
    context: Context,
    params: WorkerParameters,
    private val downloadRepository: DownloadRepository,
    private val fileManager: DownloadFileManager,
    private val httpClient: HttpClient,
    private val playbackRpcFactory: PlaybackRpcFactory,
    private val errorBus: ErrorBus,
) : CoroutineWorker(context, params) {
    companion object {
        const val KEY_AUDIO_FILE_ID = "audio_file_id"
        const val KEY_BOOK_ID = "book_id"
        const val KEY_FILENAME = "filename"
        const val KEY_FILE_SIZE = "file_size"

        private const val MAX_RETRIES = 3
    }

    override suspend fun doWork(): Result {
        val audioFileId = inputData.getString(KEY_AUDIO_FILE_ID) ?: return Result.failure()
        val bookId = inputData.getString(KEY_BOOK_ID) ?: return Result.failure()
        val filename = inputData.getString(KEY_FILENAME) ?: return Result.failure()
        val expectedSize = inputData.getLong(KEY_FILE_SIZE, 0L)

        logger.info { "Starting download: $audioFileId ($filename)" }

        downloadRepository.markDownloading(audioFileId, System.currentTimeMillis())

        return try {
            when (val result = downloadFile(audioFileId, bookId, filename, expectedSize)) {
                is AppResult.Success -> {
                    logger.info { "Download complete: $audioFileId" }
                    Result.success()
                }

                is AppResult.Failure -> {
                    handleFailure(audioFileId, result.error)
                }
            }
        } catch (e: CancellationException) {
            logger.info { "Download cancelled: $audioFileId" }
            downloadRepository.markPaused(audioFileId)
            deleteTempIfCancelled(audioFileId, bookId, filename)
            Result.failure()
        }
    }

    private suspend fun handleFailure(
        audioFileId: String,
        error: AppError,
    ): Result {
        // 401 → markPaused (don't burn the 3-attempt retry budget on unrecoverable auth).
        // installListenUpErrorHandling() routes Ktor ResponseException through ErrorMapper, which
        // types a 401 as AuthError.SessionExpired at the boundary. The global AuthFailureObserver
        // reacts to that on the foreground error bus by driving the app to login; here in the
        // background worker we simply pause so the download resumes after re-auth.
        if (error is AuthError.SessionExpired) {
            logger.warn { "Download paused due to auth failure: $audioFileId" }
            downloadRepository.markPaused(audioFileId)
            return Result.failure()
        }

        // Storage-related IO errors get classified as TransportError.NetworkUnavailable per
        // ErrorMapper's IOException branch; the user-actionable distinction lives in [debugInfo],
        // which carries the original exception message. Detect via keyword-match — imperfect but
        // matches the prior IOException string-match behavior.
        val debugInfo = error.debugInfo?.lowercase() ?: ""
        val isStorageError =
            debugInfo.contains("no space") ||
                debugInfo.contains("enospc") ||
                debugInfo.contains("disk full") ||
                debugInfo.contains("storage")

        if (isStorageError) {
            errorBus.emit(DownloadError.InsufficientStorage(debugInfo = error.debugInfo))
            logger.error { "Download failed due to insufficient storage: $audioFileId" }
            downloadRepository.markFailed(audioFileId, DownloadError.InsufficientStorage(debugInfo = error.debugInfo))
            return Result.failure()
        }

        errorBus.emit(DownloadError.DownloadFailed(debugInfo = error.debugInfo))
        logger.error { "Download failed: $audioFileId — ${error.message}" }
        // markFailed sets state=FAILED + writes errorMessage + increments retryCount in one call,
        // collapsing the previous redundant updateError + updateState(FAILED) writes (the prior
        // updateError already set state=FAILED via its underlying query — no behavior change).
        downloadRepository.markFailed(audioFileId, DownloadError.DownloadFailed(debugInfo = error.debugInfo))

        return if (runAttemptCount < MAX_RETRIES) {
            logger.info { "Will retry download: $audioFileId (attempt ${runAttemptCount + 1})" }
            Result.retry()
        } else {
            Result.failure()
        }
    }

    // If the row was explicitly cancelled by the user (state = CANCELLED), delete the partial .tmp
    // so it does not linger on disk. Network-paused downloads (state = PAUSED after markPaused)
    // keep their .tmp for Range-resume.
    // Note: cancelForBook() is called by DownloadManager after WorkManager.await(), so CANCELLED
    // may not yet be written at this point. The startup sweep in resumeIncompleteDownloads is the
    // durable catch-all; this path handles the cases where the state has already been written.
    private suspend fun deleteTempIfCancelled(
        audioFileId: String,
        bookId: String,
        filename: String,
    ) {
        val rowState = downloadRepository.getStateForAudioFile(audioFileId)
        if (rowState != DownloadState.CANCELLED) return
        val tempPath = fileManager.getAudioFilePath(bookId, audioFileId, filename, isTemp = true)
        if (!SystemFileSystem.exists(tempPath)) return
        try {
            SystemFileSystem.delete(tempPath)
            logger.info { "Deleted orphaned .tmp for cancelled download: $audioFileId" }
        } catch (deleteEx: Exception) {
            logger.warn(deleteEx) { "Failed to delete .tmp for cancelled download: $audioFileId" }
        }
    }

    private suspend fun downloadFile(
        audioFileId: String,
        bookId: String,
        filename: String,
        expectedSize: Long,
    ) = downloadAudioFile(
        audioFileId = audioFileId,
        bookId = bookId,
        filename = filename,
        expectedSize = expectedSize,
        httpClient = httpClient,
        repository = downloadRepository,
        fileManager = fileManager,
        playbackRpcFactory = playbackRpcFactory,
        isStopped = { isStopped },
        setProgress = { downloaded, total ->
            setProgress(workDataOf("progress" to downloaded, "total" to total))
        },
    )
}
