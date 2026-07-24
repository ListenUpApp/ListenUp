package com.calypsan.listenup.client.download

import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.suspendRunCatching
import com.calypsan.listenup.client.data.local.db.DownloadEntity
import com.calypsan.listenup.client.domain.repository.LocalPreferences

/**
 * Android implementation of [DownloadEnqueuer] backed by WorkManager.
 * Lifted from [DownloadManager]'s existing enqueue pattern (single-file variant).
 */
internal class AndroidDownloadEnqueuer(
    private val workManager: WorkManager,
    private val localPreferences: LocalPreferences,
) : DownloadEnqueuer {
    override suspend fun enqueue(entity: DownloadEntity): AppResult<Unit> =
        suspendRunCatching {
            val wifiOnly = localPreferences.wifiOnlyDownloads.value
            val requiredNetworkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            val workRequest =
                OneTimeWorkRequestBuilder<DownloadWorker>()
                    .setInputData(
                        workDataOf(
                            DownloadWorker.KEY_AUDIO_FILE_ID to entity.audioFileId,
                            DownloadWorker.KEY_BOOK_ID to entity.bookId,
                            DownloadWorker.KEY_FILENAME to entity.filename,
                            DownloadWorker.KEY_FILE_SIZE to entity.totalBytes,
                        ),
                    ).setConstraints(
                        Constraints
                            .Builder()
                            .setRequiredNetworkType(requiredNetworkType)
                            .build(),
                    ).addTag("download_${entity.bookId}")
                    .addTag("download_file_${entity.audioFileId}")
                    .build()

            workManager.enqueueUniqueWork(
                "download_${entity.audioFileId}",
                ExistingWorkPolicy.REPLACE,
                workRequest,
            )
        }
}
