package com.calypsan.listenup.client.download

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.error.DownloadError
import com.calypsan.listenup.client.data.local.db.DownloadEntity

/**
 * iOS no-op enqueuer. iOS downloads go through [AppleDownloadService] (NSURLSession-based)
 * which is W10 carveout territory; the sync re-enqueue path is Android-only until W10.
 */
internal class AppleDownloadEnqueuer : DownloadEnqueuer {
    override suspend fun enqueue(entity: DownloadEntity): AppResult<Unit> =
        AppResult.Failure(DownloadError.DownloadFailed(debugInfo = "Re-enqueue not supported on iOS until W10"))
}
