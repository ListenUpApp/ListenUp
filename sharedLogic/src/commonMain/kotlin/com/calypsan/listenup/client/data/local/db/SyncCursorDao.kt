package com.calypsan.listenup.client.data.local.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/**
 * DAO for the per-domain sync cursor used by the client sync engine. See
 * [SyncCursorEntity] for the cursor's role in resuming via `Last-Event-Id`.
 */
@Dao
internal interface SyncCursorDao {
    @Query("SELECT revision FROM sync_cursor WHERE domainName = :domainName")
    suspend fun getCursor(domainName: String): Long?

    @Upsert
    suspend fun setCursor(entity: SyncCursorEntity)

    /**
     * Monotonic upsert: stores [revision] only when it exceeds the domain's current
     * cursor, in one atomic statement (`MAX(existing, incoming)`). Guarantees a cursor
     * can never regress — a buffered pre-disconnect frame applied after a catch-up
     * advanced further leaves the higher value in place.
     */
    @Query(
        "INSERT INTO sync_cursor (domainName, revision) VALUES (:domainName, :revision) " +
            "ON CONFLICT(domainName) DO UPDATE SET revision = MAX(revision, excluded.revision)",
    )
    suspend fun setCursorMonotonic(
        domainName: String,
        revision: Long,
    )

    @Query("SELECT * FROM sync_cursor")
    suspend fun all(): List<SyncCursorEntity>

    @Query("DELETE FROM sync_cursor")
    suspend fun deleteAll()
}
