package com.calypsan.listenup.client.download

import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
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
            workManager.enqueueUniqueWork(
                fileWorkName(entity.audioFileId),
                // KEEP, matching DownloadManager: this seam re-enqueues at startup, where a
                // REPLACE would cancel and restart a worker that is already running correctly.
                // Changing the network policy is a different operation and lives in
                // DownloadManager.reapplyNetworkConstraints.
                ExistingWorkPolicy.KEEP,
                buildDownloadRequest(entity, localPreferences.wifiOnlyDownloads.value),
            )
        }
}
