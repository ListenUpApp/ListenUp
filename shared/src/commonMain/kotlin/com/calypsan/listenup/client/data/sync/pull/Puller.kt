package com.calypsan.listenup.client.data.sync.pull

import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.data.sync.model.SyncStatus

/**
 * Common interface for entity pullers.
 *
 * Enables testing of PullSyncOrchestrator by allowing mock implementations.
 */
interface Puller {
    /**
     * Pull entities from server with pagination.
     *
     * Returns [AppResult.Success] when all pages are fetched and persisted.
     * Returns [AppResult.Failure] on the first network or server error, leaving
     * the caller responsible for retry/log decisions.
     *
     * @param updatedAfter ISO timestamp for delta sync, null for full sync
     * @param onProgress Callback for progress updates
     */
    suspend fun pull(
        updatedAfter: String?,
        onProgress: (SyncStatus) -> Unit,
    ): AppResult<Unit>
}
