package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.domain.repository.SyncStatusRepository

/** In-memory compatibility facade until legacy timestamp sync status is fully retired from the UI. */
internal class SyncStatusRepositoryImpl : SyncStatusRepository {
    private var lastSyncTime: Timestamp? = null

    override suspend fun getLastSyncTime(): Timestamp? = lastSyncTime

    override suspend fun setLastSyncTime(timestamp: Timestamp) {
        lastSyncTime = timestamp
    }

    override suspend fun clearLastSyncTime() {
        lastSyncTime = null
    }
}
