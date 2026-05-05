package com.calypsan.listenup.client.download

import com.calypsan.listenup.api.error.DownloadError
import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.data.local.db.DownloadEntity

/**
 * Desktop no-op enqueuer. Desktop doesn't support downloads (per W8 Phase A's Desktop hide).
 */
class JvmDownloadEnqueuer : DownloadEnqueuer {
    override suspend fun enqueue(entity: DownloadEntity): AppResult<Unit> =
        AppResult.Failure(DownloadError.DownloadFailed(debugInfo = "Downloads not supported on Desktop"))
}
