package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase

/** A read of [bookId] the user finished in ListenUp at [finishedAt]: what history is made of (#1540). */
internal fun ListenUpDatabase.seedOwnRead(
    userId: String,
    bookId: String,
    readId: String,
    finishedAt: Long,
) = transaction {
    bookReadsQueries.insert(
        id = readId,
        user_id = userId,
        book_id = bookId,
        finished_at = finishedAt,
        source = "playback",
        created_at = finishedAt,
    )
}

/** A read pulled from Hardcover ([hcReadId]), as the pull writes it: never history. */
internal fun ListenUpDatabase.seedPulledRead(
    userId: String,
    bookId: String,
    hcReadId: Long,
    finishedAt: Long,
) = transaction {
    bookReadsQueries.insertPulled(
        id = pulledReadRowId(userId, hcReadId),
        user_id = userId,
        book_id = bookId,
        finished_at = finishedAt,
        created_at = finishedAt,
        hc_read_id = hcReadId,
    )
}

/** One sitting of listening to [bookId], begun at [startedAt]. */
internal fun ListenUpDatabase.seedListeningEvent(
    userId: String,
    bookId: String,
    eventId: String,
    startedAt: Long,
    endedAt: Long = startedAt + 60_000L,
) = transaction {
    listeningEventsQueries.insert(
        id = eventId,
        user_id = userId,
        book_id = bookId,
        start_position_ms = 0L,
        end_position_ms = endedAt - startedAt,
        started_at = startedAt,
        ended_at = endedAt,
        playback_speed = 1.0,
        tz = "UTC",
        device_label = null,
        revision = 1L,
        created_at = startedAt,
        updated_at = startedAt,
        deleted_at = null,
        client_op_id = null,
    )
}
