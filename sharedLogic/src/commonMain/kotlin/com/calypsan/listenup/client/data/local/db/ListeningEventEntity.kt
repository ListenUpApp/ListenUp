package com.calypsan.listenup.client.data.local.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Room entity for one closed listening span — a single uninterrupted play segment.
 *
 * Each row is append-only and represents an event where the user listened from
 * [startPositionMs] to [endPositionMs] within [bookId]. Pauses, speed changes,
 * seeks, and sleep-timer fires each close the current span and open a new one,
 * so every row has a single [playbackSpeed].
 *
 * [tz] is the IANA timezone name recorded at the time of the event (e.g.
 * `"Europe/London"`); it drives streak day-boundary math on both client and server.
 *
 * Carries the P2 sync substrate ([revision], [deletedAt]) for bidirectional
 * reconciliation with the server.
 */
@Entity(
    tableName = "listening_events",
    indices = [
        Index(value = ["userId", "endedAt"]),
        Index(value = ["userId", "revision"]),
        Index(value = ["userId", "bookId"]),
    ],
)
data class ListeningEventEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val bookId: String,
    val startPositionMs: Long,
    val endPositionMs: Long,
    /** When listening started (epoch ms). */
    val startedAt: Long,
    /** When listening ended (epoch ms). */
    val endedAt: Long,
    val playbackSpeed: Float,
    /** IANA timezone name recorded at event time (e.g. `"Europe/London"`). */
    val tz: String,
    /** Human-readable device label for multi-device history (null on older clients). */
    val deviceLabel: String?,
    /** Monotonic server revision; 0 until the server has confirmed the row. */
    val revision: Long = 0,
    /** Epoch-ms tombstone; null while the event is live. */
    val deletedAt: Long? = null,
) {
    /** Duration of this listening segment in milliseconds. */
    val durationMs: Long get() = endPositionMs - startPositionMs
}

/**
 * DAO for [ListeningEventEntity] operations.
 *
 * Provides queries for:
 * - Stats computation (events in date range, reactive)
 * - Sync operations (by revision for incremental push)
 * - History browsing (per book, per user)
 */
@Dao
interface ListeningEventDao {
    /**
     * Get all events in a date range for stats computation.
     * Returns Flow for automatic UI updates when events are added.
     */
    @Query("SELECT * FROM listening_events WHERE endedAt >= :startMs AND endedAt < :endMs ORDER BY endedAt DESC")
    fun observeEventsInRange(
        startMs: Long,
        endMs: Long,
    ): Flow<List<ListeningEventEntity>>

    /**
     * Get all events since a timestamp for stats computation.
     * No upper bound so new events are always included.
     * Returns Flow for automatic UI updates when events are added.
     */
    @Query("SELECT * FROM listening_events WHERE endedAt >= :startMs ORDER BY endedAt DESC")
    fun observeEventsSince(startMs: Long): Flow<List<ListeningEventEntity>>

    /**
     * Get all events for a specific book.
     */
    @Query("SELECT * FROM listening_events WHERE bookId = :bookId AND deletedAt IS NULL ORDER BY endedAt DESC")
    fun observeEventsForBook(bookId: String): Flow<List<ListeningEventEntity>>

    /**
     * Get total duration for events since a timestamp.
     * Uses bounds checking to prevent overflow from corrupted data.
     */
    @Query(
        """
        SELECT IFNULL(
            (SELECT SUM(duration) FROM (
                SELECT (endPositionMs - startPositionMs) as duration
                FROM listening_events
                WHERE endedAt >= :startMs
                  AND endPositionMs > startPositionMs
                  AND endPositionMs < 10000000000
                  AND startPositionMs >= 0
                  AND startPositionMs < 10000000000
            )),
            0
        )
    """,
    )
    suspend fun getTotalDurationSince(startMs: Long): Long

    /**
     * Get an event by ID.
     */
    @Query("SELECT * FROM listening_events WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ListeningEventEntity?

    /**
     * Get all events for a user and book (live rows only, newest first).
     */
    @Query(
        "SELECT * FROM listening_events WHERE userId = :userId AND bookId = :bookId AND deletedAt IS NULL ORDER BY endedAt DESC",
    )
    suspend fun getByBookForUser(
        userId: String,
        bookId: String,
    ): List<ListeningEventEntity>

    /**
     * Insert or update an event.
     */
    @Upsert
    suspend fun upsert(event: ListeningEventEntity)

    /**
     * Insert multiple events (for initial sync).
     */
    @Upsert
    suspend fun upsertAll(events: List<ListeningEventEntity>)

    /**
     * Apply a server tombstone: set the soft-delete timestamp and revision.
     */
    @Query("UPDATE listening_events SET deletedAt = :deletedAt, revision = :revision WHERE id = :id")
    suspend fun softDelete(
        id: String,
        deletedAt: Long,
        revision: Long,
    )

    /**
     * Get the most recent event timestamp for sync cursor.
     */
    @Query("SELECT MAX(endedAt) FROM listening_events")
    suspend fun getLatestEventTimestamp(): Long?

    /**
     * Get distinct dates with listening activity for streak calculation.
     * Returns dates as epoch milliseconds (start of day).
     */
    @Query(
        """
        SELECT DISTINCT (endedAt / 86400000) * 86400000 as dayStart
        FROM listening_events
        WHERE endedAt >= :startMs
        ORDER BY dayStart DESC
    """,
    )
    suspend fun getDistinctDaysWithActivity(startMs: Long): List<Long>

    // ==================== Leaderboard Aggregation Queries ====================

    /**
     * Observe total listening time since a timestamp.
     * Used for leaderboard TIME category (current user).
     * Returns Flow for reactive UI updates.
     *
     * Uses subquery with strict bounds to prevent overflow from corrupted data.
     * Returns 0 if no valid events exist.
     */
    @Query(
        """
        SELECT IFNULL(
            (SELECT SUM(duration) FROM (
                SELECT (endPositionMs - startPositionMs) as duration
                FROM listening_events
                WHERE endedAt >= :sinceMs
                  AND endPositionMs > startPositionMs
                  AND endPositionMs < 10000000000
                  AND startPositionMs >= 0
                  AND startPositionMs < 10000000000
            )),
            0
        )
    """,
    )
    fun observeTotalDurationSince(sinceMs: Long): Flow<Long>

    /**
     * Observe distinct books listened to since a timestamp.
     * Used for leaderboard BOOKS category (current user).
     * Returns Flow for reactive UI updates.
     */
    @Query(
        """
        SELECT COUNT(DISTINCT bookId)
        FROM listening_events
        WHERE endedAt >= :sinceMs
    """,
    )
    fun observeDistinctBooksSince(sinceMs: Long): Flow<Int>

    /**
     * Observe distinct days with listening activity since a timestamp.
     * Used for streak calculation (current user).
     * Returns day numbers (epochMs / 86400000) sorted descending.
     * Returns Flow for reactive updates when new events are added.
     */
    @Query(
        """
        SELECT DISTINCT (endedAt / 86400000) as dayNumber
        FROM listening_events
        WHERE endedAt >= :sinceMs
        ORDER BY dayNumber DESC
    """,
    )
    fun observeDistinctDaysSince(sinceMs: Long): Flow<List<Long>>

    /**
     * Get total duration grouped by book for a date range.
     * Uses bounds checking to prevent overflow from corrupted data.
     */
    @Query(
        """
        SELECT bookId, IFNULL(SUM(
            CASE WHEN endPositionMs > startPositionMs
                      AND endPositionMs < 10000000000
                      AND startPositionMs >= 0
                      AND startPositionMs < 10000000000
                 THEN endPositionMs - startPositionMs
                 ELSE 0
            END
        ), 0) as totalMs
        FROM listening_events
        WHERE endedAt >= :startMs AND endedAt < :endMs
        GROUP BY bookId
        ORDER BY totalMs DESC
    """,
    )
    suspend fun getDurationByBook(
        startMs: Long,
        endMs: Long,
    ): List<BookDuration>
}

/**
 * Result class for duration-by-book query.
 */
data class BookDuration(
    val bookId: String,
    val totalMs: Long,
)
