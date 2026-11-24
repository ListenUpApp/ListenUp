package com.calypsan.listenup.server.services

import kotlin.time.Instant

/** Inputs to the post-commit completion/start cascade for a saved playback position. */
internal data class PositionCascadeEvent(
    val userId: String,
    val bookId: String,
    val finished: Boolean,
    val priorFinished: Boolean,
    val existedBefore: Boolean,
    val completedAtMs: Long,
    val startedAtMs: Long?,
    val restartedAtMs: Long,
    val updatePresence: Boolean,
)

/**
 * Fires the de-nested completion/start cascade after a position row commits.
 * Shared between live [PlaybackPositionRepository.recordPositionDetailed] (`updatePresence = true`)
 * and historical import writes (`updatePresence = false`, so imported history never announces
 * stale readers as live).
 */
internal class PlaybackPositionCascade(
    private val statsRecorder: StatsRecorder?,
    private val activeSessionRepo: ActiveSessionRepository?,
) {
    suspend fun fire(event: PositionCascadeEvent) {
        if (event.finished && !event.priorFinished) {
            activeSessionRepo?.deleteForUserBook(event.userId, event.bookId)
            statsRecorder?.record(
                StatsEvent.BookCompleted(
                    userId = event.userId,
                    bookId = event.bookId,
                    occurredAt = Instant.fromEpochMilliseconds(event.completedAtMs),
                    startedAt = event.startedAtMs?.let(Instant::fromEpochMilliseconds),
                ),
            )
        } else if (!event.finished) {
            if (event.updatePresence) {
                activeSessionRepo?.startOrRefresh(event.userId, event.bookId)
            }
            if (!event.existedBefore) {
                statsRecorder?.record(
                    StatsEvent.BookRestarted(
                        userId = event.userId,
                        bookId = event.bookId,
                        occurredAt = Instant.fromEpochMilliseconds(event.restartedAtMs),
                        isReread = false,
                    ),
                )
            } else if (event.priorFinished) {
                statsRecorder?.record(
                    StatsEvent.BookRestarted(
                        userId = event.userId,
                        bookId = event.bookId,
                        occurredAt = Instant.fromEpochMilliseconds(event.restartedAtMs),
                        isReread = true,
                    ),
                )
            }
        }
    }
}
