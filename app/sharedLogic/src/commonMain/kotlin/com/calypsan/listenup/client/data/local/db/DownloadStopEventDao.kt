package com.calypsan.listenup.client.data.local.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query

/** The bounded history of download stops ([DownloadStopEventEntity]). Written only through `BookTransferStore`. */
@Dao
internal interface DownloadStopEventDao {
    /** Add one stop to the history. */
    @Insert
    suspend fun insertStopEvent(event: DownloadStopEventEntity)

    /** Drop all but the newest [keep] stop events. */
    @Query(
        "DELETE FROM download_stop_events WHERE id NOT IN " +
            "(SELECT id FROM download_stop_events ORDER BY id DESC LIMIT :keep)",
    )
    suspend fun pruneStopEvents(keep: Int)

    /** The newest [limit] stop events, newest first. */
    @Query("SELECT * FROM download_stop_events ORDER BY id DESC LIMIT :limit")
    suspend fun recentStopEvents(limit: Int): List<DownloadStopEventEntity>
}
