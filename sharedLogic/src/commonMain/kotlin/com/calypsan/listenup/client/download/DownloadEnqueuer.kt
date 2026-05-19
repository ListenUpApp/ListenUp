package com.calypsan.listenup.client.download

import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.client.data.local.db.DownloadEntity

/**
 * Platform abstraction for enqueueing a single download worker. Android: backed by WorkManager.
 * iOS / Desktop: no-op (iOS uses NSURLSession-driven downloads via AppleDownloadService; Desktop
 * doesn't download).
 *
 * Used by [com.calypsan.listenup.client.domain.repository.DownloadRepository.resumeForAudioFile]
 * to re-enqueue a worker after the SSE `transcode.complete` event re-activates a WAITING_FOR_SERVER row.
 */
interface DownloadEnqueuer {
    /**
     * Enqueue a single audio-file download with `ExistingWorkPolicy.REPLACE` semantics.
     * Returns [AppResult.Success] on success; [AppResult.Failure] if the platform doesn't support
     * downloads (Desktop, iOS in Phase D) or if WorkManager rejects the request.
     */
    suspend fun enqueue(entity: DownloadEntity): AppResult<Unit>
}
