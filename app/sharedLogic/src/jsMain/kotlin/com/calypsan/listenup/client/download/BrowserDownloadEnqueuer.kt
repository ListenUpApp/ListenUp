package com.calypsan.listenup.client.download

import com.calypsan.listenup.api.error.DownloadError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.DownloadEntity

/**
 * Browser enqueuer: there is nothing to enqueue.
 *
 * Same shape as [JvmDownloadEnqueuer] on desktop, and for the same reason — offline audio in a
 * browser is undesigned (see [DownloadFileManager]), so the truthful binding refuses rather than
 * pretends. It returns a typed failure instead of throwing because `DownloadRepository` walks this
 * seam while a book detail loads: a throw here would strand a page that never asked to download
 * anything.
 */
internal class BrowserDownloadEnqueuer : DownloadEnqueuer {
    override suspend fun enqueue(entity: DownloadEntity): AppResult<Unit> =
        AppResult.Failure(DownloadError.DownloadFailed(debugInfo = "Downloads are not supported in a browser"))
}
