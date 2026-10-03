package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.UserStatsSyncPayload
import com.calypsan.listenup.domain.stats.StatsWindow
import com.calypsan.listenup.domain.stats.StreakReducer
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDate
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
    // 1. Read all non-deleted listening_events for this user, ordered by endedAt ascending.
    val events =
        suspendTransaction(sql) {
            sql.listeningEventsQueries
                .selectForUserOrderedByEndedAt(userId)
                .executeAsList()
        }

    // 2. Walk events for the all-time total, distinct started books, and the per-day listening set.
    val userTz = tz ?: sql.homeTimeZone(userId)
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

    // 3. Window sums over the shared calendar windows (local midnight in the home timezone), counting
    //    each span whole when it ended inside the window — the same rule Home applies.
    val now = Instant.fromEpochMilliseconds(nowMs)
    val weekStart = StatsWindow.Week.startMs(now, userTz)
    val monthStart = StatsWindow.Month.startMs(now, userTz)
    var last7 = 0L
    var last30 = 0L
    for (event in events) {
        last7 += StatsWindow.secondsCounted(event.started_at, event.ended_at, weekStart)
        last30 += StatsWindow.secondsCounted(event.started_at, event.ended_at, monthStart)
    }

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
        streakDayInstantsMs(sql, userId, events.map { it.ended_at }).map { localDayOf(it, userTz) }

    // 6. Current + longest streak via the shared reducer, resolved as-of-today in the home timezone.
    val today = now.toLocalDateTime(userTz).date
    val streaks = StreakReducer.reduce(streakDays, today)

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
