package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.UserStatsSyncPayload
import com.calypsan.listenup.domain.stats.StatsWindow
import com.calypsan.listenup.domain.stats.StreakReducer
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Insert a `source = "reconcile"` `book_reads` row for every finished, non-deleted `playback_positions`
 * row that has no completion row yet — recovering finishes lost to a crash between the position commit
 * and the completion cascade, and backfilling pre-Spec-004 imports that never wrote `book_reads`.
 * Idempotent: a book that already has any completion row is skipped. Runs as the first step of a full
 * rebuild ([UserStatsBackfillService.backfillFor]) so the re-derived `booksFinished` — counted from
 * `book_reads` — reflects every finished book. Re-read multiplicity for pre-crash finishes is
 * unrecoverable; reconcile restores at least one read per finished book, never invents extras.
 */
suspend fun reconcileBookReadsFromPositions(
    sql: ListenUpDatabase,
    userId: String,
    nowMs: Long,
) {
    suspendTransaction(sql) {
        sql.playbackPositionsQueries.selectFinishedBooksForUser(userId).executeAsList().forEach { finished ->
            val alreadyLogged = sql.bookReadsQueries.existsForUserBook(userId, finished.book_id).executeAsOne()
            if (!alreadyLogged) {
                sql.bookReadsQueries.insert(
                    id = Uuid.random().toString(),
                    user_id = userId,
                    book_id = finished.book_id,
                    // The day the reader said they finished, when the position kept one — the
                    // completion cascade that was lost would have dated the read by it too.
                    finished_at = resolveFinishedAt(finished.finished_at, nowMs) ?: finished.last_played_at,
                    source = BookReadSource.RECONCILE,
                    created_at = nowMs,
                )
            }
        }
    }
}

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
 *
 * @param tz a caller-provided memo of `sql.homeTimeZone(userId)` — never a different zone. Pass
 * `null` (default) to have this function read it itself; callers that already read the home
 * timezone for another step of the same cascade (e.g. [StatsRecorder]) should pass it through to
 * avoid a second read of the same row.
 */
suspend fun deriveUserStats(
    sql: ListenUpDatabase,
    userId: String,
    nowMs: Long,
    tz: TimeZone? = null,
): UserStatsSyncPayload {
    val userTz = tz ?: sql.homeTimeZone(userId)
    val now = Instant.fromEpochMilliseconds(nowMs)
    val weekStart = StatsWindow.Week.startMs(now, userTz)
    val monthStart = StatsWindow.Month.startMs(now, userTz)

    // 1–3. Every listening field still comes from ALL committed events, but as SQL aggregates rather than a
    //    walk over loaded rows: the all-time total and distinct started books, the two calendar-window sums
    //    (local midnight in the home timezone, each span counted whole when it ended inside the window — the
    //    rule Home applies, StatsWindow.secondsCounted), and the ended-at instants for the day set. Day
    //    bucketing stays in Kotlin, in the home timezone, so DST and fractional offsets stay exact.
    val listening =
        suspendTransaction(sql) {
            val queries = sql.listeningEventsQueries
            val totals = queries.statsTotalsForUser(userId).executeAsOne()
            ListeningAggregates(
                // COALESCE makes it non-null; SQLDelight types a CAST column in a multi-column row as nullable.
                totalSeconds = totals.total_seconds ?: 0L,
                booksStarted = totals.books_started.toInt(),
                secondsSinceWeekStart = queries.statsSecondsEndedSinceForUser(userId, weekStart).executeAsOne(),
                secondsSinceMonthStart = queries.statsSecondsEndedSinceForUser(userId, monthStart).executeAsOne(),
                endedAtAscending = queries.selectEndedAtForUserSince(userId, Long.MIN_VALUE).executeAsList(),
            )
        }
    val listeningDays = listening.endedAtAscending.map { localDayOf(it, userTz) }

    // 4. Finished-book count from the `book_reads` primitive (re-reads counted). All-time and windowed
    //    finished-books now derive from the same table, so they cannot disagree.
    val booksFinished =
        suspendTransaction(sql) {
            sql.bookReadsQueries
                .countForUser(userId)
                .executeAsOne()
                .toInt()
        }

    // 5. Streak day-set: union the listening_events days with the days the user advanced progress
    //    (playback_positions.last_played_at) or finished a book (book_reads.finished_at). ABS imports
    //    write mediaProgress → positions + completions but keep session rows (→ listening_events)
    //    sparsely, so events alone leave false gaps that collapse the streak. The client's
    //    StatsRepositoryImpl unions the primitives it has locally (positions' last-played + latest
    //    finishedAt); this derivation additionally counts every historical `book_reads` finish day, so
    //    the two streaks match except for re-read finish days the client's last-write-wins position
    //    cannot see. That divergence is accepted: Home shows the locally-derived streak, leaderboards
    //    show this one (pinned by UserStatsDerivationStreakDivergenceTest).
    val streakDays =
        listeningDays +
            streakDayInstantsMs(sql, userId, listeningEndedAtMs = emptyList()).map { localDayOf(it, userTz) }

    // 6. Current + longest streak via the shared reducer, resolved as-of-today in the home timezone.
    val today = now.toLocalDateTime(userTz).date
    val streaks = StreakReducer.reduce(streakDays, today)

    return UserStatsSyncPayload(
        id = userId,
        totalSecondsAllTime = listening.totalSeconds,
        totalSecondsLast7Days = listening.secondsSinceWeekStart,
        totalSecondsLast30Days = listening.secondsSinceMonthStart,
        booksStarted = listening.booksStarted,
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

/** What [deriveUserStats] reads from `listening_events`: the aggregates, plus the instants the day set is built from. */
private data class ListeningAggregates(
    val totalSeconds: Long,
    val booksStarted: Int,
    val secondsSinceWeekStart: Long,
    val secondsSinceMonthStart: Long,
    val endedAtAscending: List<Long>,
)
