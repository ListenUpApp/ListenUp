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
 * Carries the sync substrate ([revision], [deletedAt]) for bidirectional
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
internal data class ListeningEventEntity(
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
internal interface ListeningEventDao {
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
     * Observe all live events for a specific user and book, newest-first by [endedAt].
     *
     * Tombstoned events ([deletedAt] != null) are excluded. Re-emits whenever any
     * row in `listening_events` changes — Room reactive semantics.
     *
     * @param userId The user whose events to observe.
     * @param bookId The book whose history to stream.
     */
    @Query(
        """
        SELECT * FROM listening_events
        WHERE userId = :userId AND bookId = :bookId AND deletedAt IS NULL
        ORDER BY endedAt DESC
    """,
    )
    fun observeByBookForUser(
        userId: String,
        bookId: String,
    ): Flow<List<ListeningEventEntity>>

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
     * Advance only the revision of an existing row (append-only re-apply). Domain fields are never
     * mutated, but converging the revision when the server re-upserts an id (idempotent replay /
     * backfill) keeps the `(id, revision)` digest from permanently drifting on that client. See
     * [com.calypsan.listenup.client.data.sync.domains.AppendOnlyMirrorApply].
     */
    @Query("UPDATE listening_events SET revision = :revision WHERE id = :id")
    suspend fun updateRevision(
        id: String,
        revision: Long,
    )

    /**
     * Resurrect a locally-tombstoned event: clear its tombstone and align its revision. Used when a
     * row is re-delivered LIVE (`deletedAt = null`) after having been soft-deleted — `deletedAt` is
     * sync substrate, not append-only content, so without this the row would stay tombstoned forever
     * and no reconcile could heal it (the digests would agree on `(id, revision)`). See
     * [com.calypsan.listenup.client.data.sync.domains.AppendOnlyMirrorApply].
     */
    @Query("UPDATE listening_events SET deletedAt = NULL, revision = :revision WHERE id = :id")
    suspend fun restore(
        id: String,
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
     * Observe events for a specific user within a time window (exclusive upper bound).
     *
     * Scoped to [userId] so cross-user events never contaminate stats. Tombstoned
     * events ([deletedAt] != null) are excluded — they represent server-reconciled
     * deletes and must not inflate stats.
     *
     * @param userId The user whose events to observe.
     * @param startMs Inclusive lower bound (epoch ms) on [endedAt].
     * @param endMs Exclusive upper bound (epoch ms) on [endedAt].
     * @return Flow that re-emits the full matching list whenever any row in
     *   [tableName] changes (Room reactive semantics).
     */
    @Query(
        """
        SELECT * FROM listening_events
        WHERE userId = :userId
          AND endedAt >= :startMs
          AND endedAt < :endMs
          AND deletedAt IS NULL
        ORDER BY endedAt DESC
    """,
    )
    fun observeWithinWindow(
        userId: String,
        startMs: Long,
        endMs: Long,
    ): Flow<List<ListeningEventEntity>>

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

    /** All rows (including tombstones) with [revision][ListeningEventEntity.revision] <= [max], for digest computation. */
    @Query("SELECT id AS id, revision FROM listening_events WHERE revision <= :max")
    suspend fun digestRows(max: Long): List<IdRevision>

    /**
     * Observe every live event's [endedAt] for [userId], oldest-first. Drives the
     * client-side streak computation — the week window isn't enough because the longest streak
     * spans all history. A list of longs is cheap; only distinct calendar days matter downstream.
     *
     * Covered by the `["userId","endedAt"]` index; this query is fully indexed.
     */
    @Query("SELECT endedAt FROM listening_events WHERE userId = :userId AND deletedAt IS NULL ORDER BY endedAt")
    fun observeEndedAt(userId: String): Flow<List<Long>>

    /**
     * One-time repair: re-stamp rows poisoned with a blank userId (written during a startup
     * catch-up before auth resolved) with the now-known signed-in [userId]. Idempotent — a no-op
     * once no blank rows remain. Returns the number of rows updated.
     */
    @Query("UPDATE listening_events SET userId = :userId WHERE userId = ''")
    suspend fun reassignBlankUserId(userId: String): Int
}

/**
 * Result class for duration-by-book query.
 */
internal data class BookDuration(
    val bookId: String,
    val totalMs: Long,
)
