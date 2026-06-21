package com.calypsan.listenup.client.domain.model

/**
 * Domain representation of a single closed listening span — one uninterrupted play segment.
 *
 * Each instance represents an event where the user listened from [startPositionMs] to
 * [endPositionMs] within [bookId]. Pauses, speed changes, seeks, and sleep-timer fires each
 * close the current span and open a new one, so every instance carries a single [playbackSpeed].
 *
 * [tz] is the IANA timezone name recorded at the time of the event (e.g. `"Europe/London"`);
 * it drives streak day-boundary math on both client and server.
 *
 * Mapping from the data layer happens in
 * [com.calypsan.listenup.client.data.repository.ListeningEventRepositoryImpl].
 *
 * @property id Stable identifier for this event.
 * @property userId The user this event belongs to.
 * @property bookId The book the user was listening to.
 * @property startPositionMs Position (ms) within the book where listening started.
 * @property endPositionMs Position (ms) within the book where listening ended.
 * @property startedAt Epoch-ms timestamp when listening started.
 * @property endedAt Epoch-ms timestamp when listening ended.
 * @property playbackSpeed Speed at which the content was played.
 * @property tz IANA timezone name recorded at event time (e.g. `"Europe/London"`).
 * @property deviceLabel Human-readable device label; null on older clients.
 */
data class ListeningEvent(
    val id: String,
    val userId: String,
    val bookId: String,
    val startPositionMs: Long,
    val endPositionMs: Long,
    val startedAt: Long,
    val endedAt: Long,
    val playbackSpeed: Float,
    val tz: String,
    val deviceLabel: String?,
) {
    /** Duration of this listening segment in milliseconds. */
    val durationMs: Long get() = endPositionMs - startPositionMs
}

/**
 * Total listening duration aggregated per book over a time window.
 *
 * Produced by [com.calypsan.listenup.client.domain.repository.ListeningEventRepository.getDurationByBook]
 * and consumed by stats and leaderboard features.
 *
 * @property bookId The book whose listening time is aggregated.
 * @property totalMs Total listening duration in milliseconds across the queried window.
 */
data class BookListeningDuration(
    val bookId: String,
    val totalMs: Long,
)
