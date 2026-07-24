package com.calypsan.listenup.client.data.local.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/**
 * DAO for [TentativeSpanEntity] — the local-only single-row crash-recovery table.
 *
 * Only one span can be open at a time (a user listens to one book at a time),
 * so the table contains at most one row. The row is written on playback start,
 * updated on each heartbeat tick, and deleted when the span closes (pause,
 * book-end, speed change, seek). On app restart, an orphan row is promoted to a
 * [ListeningEventEntity] before playback resumes.
 *
 * This table is **not synced** to the server.
 */
@Dao
internal interface TentativeSpanDao {
    /**
     * Get the current open span, or null if no span is in progress.
     */
    @Query("SELECT * FROM tentative_span LIMIT 1")
    suspend fun get(): TentativeSpanEntity?

    /**
     * Number of rows currently in the table — asserts the single-row invariant [get] otherwise
     * takes on faith (its `LIMIT 1` silently hides a second row instead of surfacing it).
     */
    @Query("SELECT COUNT(*) FROM tentative_span")
    suspend fun countRows(): Int

    /**
     * Write or replace the open span. Because there is at most one row, this
     * effectively replaces any existing row.
     */
    @Upsert
    suspend fun upsertSingleton(span: TentativeSpanEntity)

    /**
     * Delete the open span once it has been finalized into a [ListeningEventEntity].
     */
    @Query("DELETE FROM tentative_span")
    suspend fun delete()
}
