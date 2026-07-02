package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.UserStatsSyncPayload
import com.calypsan.listenup.domain.stats.StreakReducer
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime

/**
 * The single pure re-derivation of a user's `user_stats` from the durable primitives
 * (`listening_events` + finished `playback_positions`), computed as of [nowMs] in the user's home
 * timezone.
 *
 * Shared verbatim by both stats-writing paths — [StatsRecorder]'s per-event `ListeningSessionClosed`
 * cascade and [UserStatsBackfillService.backfillFor] — so the live row and a full rebuild can never
 * disagree, and every field is a pure function of the committed events. That is what makes the stats
 * self-healing (a crash that skips one event's cascade is recovered by the next event's re-derive) and
 * order-independent (out-of-order offline-outbox replay converges to the same row as sorted arrival).
 *
 * Reads only — the caller upserts the returned payload. `revision`/timestamps are left at 0 for the
 * syncable substrate to assign on write.
 *
 * All day-boundary math uses the user's home timezone (one consistent frame per user); the per-event
 * `tz` column is deliberately ignored because it can be "UTC" for ABS imports and mixed-frame for
 * travelers, which would produce wrong streaks.
 */
suspend fun deriveUserStats(
    sql: ListenUpDatabase,
    userId: String,
    nowMs: Long,
): UserStatsSyncPayload {
    // 1. Read all non-deleted listening_events for this user, ordered by endedAt ascending.
    val events =
        suspendTransaction(sql) {
            sql.listeningEventsQueries
                .selectForUserOrderedByEndedAt(userId)
                .executeAsList()
        }

    // 2. Walk events for the all-time total, distinct started books, and the per-day listening set.
    val userTz = sql.homeTimeZone(userId)
    var totalAllTime = 0L
    val distinctBooks = mutableSetOf<String>()
    val listeningDays = ArrayList<LocalDate>(events.size)
    for (event in events) {
        totalAllTime += (event.ended_at - event.started_at) / 1_000L
        distinctBooks.add(event.book_id)
        listeningDays +=
            Instant
                .fromEpochMilliseconds(event.ended_at)
                .toLocalDateTime(userTz)
                .date
    }

    // 3. Rolling-window sums against nowMs, clipping any span that straddles the cutoff.
    val cutoff7 = nowMs - 7 * 86_400_000L
    val cutoff30 = nowMs - 30 * 86_400_000L
    var last7 = 0L
    var last30 = 0L
    for (event in events) {
        val endedAtMs = event.ended_at
        if (endedAtMs >= cutoff7) last7 += (endedAtMs - maxOf(event.started_at, cutoff7)) / 1_000L
        if (endedAtMs >= cutoff30) last30 += (endedAtMs - maxOf(event.started_at, cutoff30)) / 1_000L
    }

    // 4. Finished-book count (non-deleted positions). Spec 004 will move this onto the `book_reads`
    //    primitive; kept here for now so the incremental and rebuilt rows agree.
    val booksFinished =
        suspendTransaction(sql) {
            sql.playbackPositionsQueries
                .countFinishedForUser(userId)
                .executeAsOne()
                .toInt()
        }

    // 5. Current + longest streak via the shared reducer, resolved as-of-today in the home timezone.
    val today = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(userTz).date
    val streaks = StreakReducer.reduce(listeningDays, today)

    return UserStatsSyncPayload(
        id = userId,
        totalSecondsAllTime = totalAllTime,
        totalSecondsLast7Days = last7,
        totalSecondsLast30Days = last30,
        booksStarted = distinctBooks.size,
        booksFinished = booksFinished,
        currentStreakDays = streaks.current,
        longestStreakDays = streaks.longest,
        lastEventDate = listeningDays.lastOrNull()?.toString(),
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )
}
