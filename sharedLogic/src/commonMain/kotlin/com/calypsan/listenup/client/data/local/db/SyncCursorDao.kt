package com.calypsan.listenup.client.data.local.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/**
 * DAO for the per-domain sync cursor used by the client sync engine. See
 * [SyncCursorEntity] for the cursor's role in resuming via `Last-Event-Id`.
 */
@Dao
interface SyncCursorDao {
    @Query("SELECT revision FROM sync_cursor WHERE domainName = :domainName")
    suspend fun getCursor(domainName: String): Long?

    @Upsert
    suspend fun setCursor(entity: SyncCursorEntity)

    @Query("SELECT * FROM sync_cursor")
    suspend fun all(): List<SyncCursorEntity>
}
