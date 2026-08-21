package com.calypsan.listenup.client.download

import com.calypsan.listenup.api.error.DownloadError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.DownloadEntity

/**
 * Desktop no-op enqueuer. Desktop doesn't support downloads.
 *
 * [DownloadError.NotSupported] (not `DownloadFailed`) because this is not a transient failure
 * worth retrying — desktop will never be able to download, no matter how many times it is asked —
 * matching `NoDownloadsService.downloadBook` and the browser's `BrowserDownloadEnqueuer`.
 */
internal class JvmDownloadEnqueuer : DownloadEnqueuer {
    override suspend fun enqueue(entity: DownloadEntity): AppResult<Unit> =
        AppResult.Failure(DownloadError.NotSupported(debugInfo = "Downloads not supported on Desktop"))
}
