package com.calypsan.listenup.client.data.local.db

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert

/**
 * DAO for [TentativeSpanEntity] — the local-only crash-recovery table of listening spans not yet
 * finalized.
 *
 * Only one span is open at a time (a user listens to one book at a time). The open span is written
 * on playback start, updated on each heartbeat tick, and deleted when the span closes (pause,
 * book-end, speed change, seek). On app restart, every row a prior process left behind is promoted
 * to a [ListeningEventEntity] before playback resumes.
 *
 * The one exception to a single row: a span whose finalize kept failing is set aside, and stays
 * here beside the open one so the next launch can still recover it (see
 * [com.calypsan.listenup.client.playback.ListeningEventRecorder]).
 *
 * This table is **not synced** to the server.
 */
@Dao
internal interface TentativeSpanDao {
    /**
     * Get the oldest span in the table, or null if there is none.
     */
    @Query("SELECT * FROM tentative_span ORDER BY startedAt LIMIT 1")
    suspend fun get(): TentativeSpanEntity?

    /**
     * Every span in the table, oldest first: the open one and any set aside.
     */
    @Query("SELECT * FROM tentative_span ORDER BY startedAt")
    suspend fun getAll(): List<TentativeSpanEntity>

    /**
     * Number of rows currently in the table — asserts what [get]'s `LIMIT 1` would otherwise hide.
     */
    @Query("SELECT COUNT(*) FROM tentative_span")
    suspend fun countRows(): Int

    /**
     * Write or replace the span with [span]'s id.
     */
    @Upsert
    suspend fun upsert(span: TentativeSpanEntity)

    /**
     * Delete the span with [id] once it has been finalized into a [ListeningEventEntity].
     */
    @Query("DELETE FROM tentative_span WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * Delete every span, for a library reset that ends the session they belong to.
     */
    @Query("DELETE FROM tentative_span")
    suspend fun deleteAll()
}
