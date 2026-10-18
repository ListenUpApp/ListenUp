package com.calypsan.listenup.client.data.local.db

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

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

    /**
     * The live-frame advance: moves [domainName]'s cursor up to [revision] (`MAX`, never back), but only when the
     * domain already has a cursor. It never creates one. Pull is the correctness path: a cursor is created only by a
     * catch-up page that applied, because a cursor created by a live frame would make the next catch-up start past
     * history this device has never pulled, and would claim the domain had synced when it had not.
     */
    @Query("UPDATE sync_cursor SET revision = MAX(revision, :revision) WHERE domainName = :domainName")
    suspend fun advanceExistingCursor(
        domainName: String,
        revision: Long,
    )

    /**
     * The domain's cursor as it changes; null until at least one catch-up page for it has applied, or an empty pull
     * of it has completed. Not proof the whole pull completed: a later page can still fail.
     */
    @Query("SELECT revision FROM sync_cursor WHERE domainName = :domainName")
    fun observeCursor(domainName: String): Flow<Long?>

    @Query("SELECT * FROM sync_cursor")
    suspend fun all(): List<SyncCursorEntity>

    @Query("DELETE FROM sync_cursor")
    suspend fun deleteAll()
}
