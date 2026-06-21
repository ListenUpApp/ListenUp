package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.domain.model.BookListeningDuration
import com.calypsan.listenup.client.domain.model.ListeningEvent
import kotlinx.coroutines.flow.Flow

/**
 * Repository for listening events.
 *
 * Encapsulates the two-write atomic operation (local upsert + pending-op queue) behind
 * a single transactional call. All read methods surface DAO queries to callers that
 * currently access [com.calypsan.listenup.client.data.local.db.ListeningEventDao] directly
 * (Stats, Leaderboard) — so those callers can migrate through this interface later.
 *
 * Read methods return domain types ([ListeningEvent], [BookListeningDuration]); mapping
 * from the data layer happens once in the implementation.
 */
interface ListeningEventRepository {
    /**
     * Save a listening event locally (Room-only write).
     *
     * Writes through [com.calypsan.listenup.client.data.local.db.ListeningEventDao.upsert]
     * inside a [com.calypsan.listenup.client.data.local.db.TransactionRunner.atomically] block.
     * The server-sync pending-op is **not** enqueued here — the P2 canonical recording path
     * ([com.calypsan.listenup.client.playback.ListeningEventRecorder.finalizeCurrentSpan])
     * handles the pending-op enqueue directly.
     *
     * [kotlinx.coroutines.CancellationException] is always rethrown (EM-R1).
     */
    suspend fun queueListeningEvent(
        bookId: BookId,
        startPositionMs: Long,
        endPositionMs: Long,
        startedAt: Long,
        endedAt: Long,
        playbackSpeed: Float,
    ): AppResult<Unit>

    // ==================== Read methods (external callers today) ====================

    /** All events for [bookId], newest first. Used by future per-book stats. */
    fun observeEventsForBook(bookId: String): Flow<List<ListeningEvent>>

    /** Events in [startMs]..[endMs] window, newest first. */
    fun observeEventsInRange(
        startMs: Long,
        endMs: Long,
    ): Flow<List<ListeningEvent>>

    /** Events since [startMs], no upper bound, newest first. */
    fun observeEventsSince(startMs: Long): Flow<List<ListeningEvent>>

    /** Total listening duration (ms) across events since [startMs]. */
    suspend fun getTotalDurationSince(startMs: Long): Long

    /** Reactive total listening duration since [startMs]. */
    fun observeTotalDurationSince(startMs: Long): Flow<Long>

    /** Reactive distinct-book count since [startMs]. */
    fun observeDistinctBooksSince(startMs: Long): Flow<Int>

    /** Reactive distinct days (epoch ms) with listening activity since [startMs]. */
    fun observeDistinctDaysSince(startMs: Long): Flow<List<Long>>

    /** Distinct days (epoch ms) with listening activity since [startMs] (one-shot). */
    suspend fun getDistinctDaysWithActivity(startMs: Long): List<Long>

    /** Total duration grouped by book for [startMs]..[endMs]. */
    suspend fun getDurationByBook(
        startMs: Long,
        endMs: Long,
    ): List<BookListeningDuration>
}
