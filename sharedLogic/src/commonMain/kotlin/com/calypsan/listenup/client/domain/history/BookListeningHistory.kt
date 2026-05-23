package com.calypsan.listenup.client.domain.history

import kotlinx.datetime.LocalDate

/**
 * Per-book listening history for one user, day-grouped. Backed by P2's
 * `listening_events` table via [com.calypsan.listenup.client.domain.repository.BookListeningHistoryRepository].
 * Day buckets are sorted newest-first.
 */
data class BookListeningHistory(val daily: List<DayBucket>)

/**
 * One day in the history timeline. [relativeLabel] is "Today" / "Yesterday" /
 * "May 19" / "May 19, 2025" computed relative to the viewer's current local date.
 * [events] is newest-first within the day; [totalSeconds] is the sum of all event
 * wall-clock durations in this day's bucket.
 */
data class DayBucket(
    val date: LocalDate,
    val relativeLabel: String,
    val totalSeconds: Long,
    val events: List<EventEntry>,
)

/**
 * One closed listening span — the per-event row in the per-book history timeline.
 */
data class EventEntry(
    val id: String,
    val startedAt: Long,
    val endedAt: Long,
    val startPositionMs: Long,
    val endPositionMs: Long,
    val playbackSpeed: Float,
    val deviceLabel: String?,
)
