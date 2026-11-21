package com.calypsan.listenup.client.download

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.suspendRunCatching
import com.calypsan.listenup.client.data.local.db.DownloadEntity

/**
 * Android implementation of [DownloadEnqueuer]: a row's book is scheduled as one run through [BookTransfers], the
 * only place download work reaches the platform. A book already scheduled or running is left as it is.
 */
internal class AndroidDownloadEnqueuer(
    private val transfers: Lazy<BookTransfers>,
) : DownloadEnqueuer {
    override suspend fun enqueue(entity: DownloadEntity): AppResult<Unit> =
        suspendRunCatching { transfers.value.schedule(entity.bookId) }
}
