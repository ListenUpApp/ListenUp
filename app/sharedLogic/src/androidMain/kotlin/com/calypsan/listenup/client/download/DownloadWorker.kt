package com.calypsan.listenup.client.download

import android.content.Context
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.error.ErrorBus
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException

private val logger = KotlinLogging.logger {}

/**
 * WorkManager worker that downloads a single audio file.
 *
 * Features:
 * - Signed URL resolution via [PlaybackService.prepare]
 * - Resume support (Range headers)
 * - Progress updates
 * - Cancellation handling: a stop pauses the row and is logged with WorkManager's stop reason
 * - Retry: a lost connection waits for the network without spending a retry; a genuine failure is
 *   retried against the row's own budget (see [settleDownloadFailure]), never `runAttemptCount`,
 *   which WorkManager also advances for every run the system stopped
 */
class DownloadWorker(
    context: Context,
    params: WorkerParameters,
    private val downloadRepository: DownloadRepository,
    private val fileManager: DownloadFileManager,
    private val audioFileDownloader: AudioFileDownloader,
    private val errorBus: ErrorBus,
) : CoroutineWorker(context, params) {
    companion object {
        const val KEY_AUDIO_FILE_ID = "audio_file_id"
        const val KEY_BOOK_ID = "book_id"
        const val KEY_FILENAME = "filename"
        const val KEY_FILE_SIZE = "file_size"
    }

    // The cancellation IS re-thrown — after the pause is persisted under NonCancellable, which must land first.
    @Suppress("SuspendFunSwallowedCancellation")
    override suspend fun doWork(): Result {
        val audioFileId = inputData.getString(KEY_AUDIO_FILE_ID) ?: return Result.failure()
        val bookId = inputData.getString(KEY_BOOK_ID) ?: return Result.failure()
        val filename = inputData.getString(KEY_FILENAME) ?: return Result.failure()
        val expectedSize = inputData.getLong(KEY_FILE_SIZE, 0L)

        logger.info { "download: start file=$audioFileId book=$bookId attempt=$runAttemptCount" }

        // Optimistic start state; a later terminal write (markCompleted/markFailed/markPaused)
        // always overwrites it, so a dropped status write here self-corrects.
        val _ = downloadRepository.markDownloading(audioFileId, System.currentTimeMillis())

        return try {
            when (
                val result =
                    downloadFile(
                        audioFileId = audioFileId,
                        bookId = bookId,
                        filename = filename,
                        expectedSize = expectedSize,
                    )
            ) {
                is AppResult.Success -> {
                    logger.info { "Download complete: $audioFileId" }
                    Result.success()
                }

                is AppResult.Failure -> {
                    val verdict =
                        settleDownloadFailure(
                            audioFileId = audioFileId,
                            error = result.error,
                            repository = downloadRepository,
                            errorBus = errorBus,
                        )
                    when (verdict) {
                        DownloadFailureVerdict.RETRY_LATER -> Result.retry()
                        DownloadFailureVerdict.GIVE_UP -> Result.failure()
                    }
                }
            }
        } catch (e: CancellationException) {
            logger.info {
                "download: stopped file=$audioFileId book=$bookId reason=${lastStopReason()} attempt=$runAttemptCount"
            }
            // Persist the pause + temp cleanup under NonCancellable (the scope is already being
            // cancelled here), then re-throw so the worker is recorded as cancelled, not failed.
            persistDownloadCancellation(
                repository = downloadRepository,
                fileManager = fileManager,
                audioFileId = audioFileId,
                bookId = bookId,
                filename = filename,
            )
            throw e
        }
    }

    /**
     * Why WorkManager stopped this run, by name. The reason is only readable from Android 12
     * (API 31); below that a stop is recorded as unavailable rather than guessed at.
     */
    private fun lastStopReason(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            downloadStopReasonName(stopReason)
        } else {
            "UNAVAILABLE_BELOW_API_31"
        }

    private suspend fun downloadFile(
        audioFileId: String,
        bookId: String,
        filename: String,
        expectedSize: Long,
    ) = audioFileDownloader.download(
        audioFileId = audioFileId,
        bookId = bookId,
        filename = filename,
        expectedSize = expectedSize,
        isStopped = { isStopped },
        setProgress = { downloaded, total ->
            setProgress(workDataOf("progress" to downloaded, "total" to total))
        },
    )
}
