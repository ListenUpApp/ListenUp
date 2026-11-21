package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.UserStatsSyncPayload
import com.calypsan.listenup.domain.stats.StatsWindow
import com.calypsan.listenup.domain.stats.StreakReducer
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.random.Random
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * [deriveUserStats] recomputes every field from all committed events in SQL aggregates instead of walking every row.
 * This pins it to the row walk it replaced ([rowWalkDerivation], kept here verbatim as the oracle) over randomly
 * generated histories: spans that straddle a DST change, a half-hour and a three-quarter-hour offset zone,
 * sub-second and negative spans, deleted events, and the position / completion days the streak unions in.
 */
class UserStatsDerivationOracleTest :
    FunSpec({
        // Europe/London springs forward at 2026-03-29T01:00Z and falls back at 2026-10-25T01:00Z.
        val springForward = 1_774_746_000_000L
        val fallBack = 1_792_890_000_000L
        val zones = listOf("Europe/London", "Asia/Kolkata", "Asia/Kathmandu", "America/St_Johns", "UTC")

        zones.forEach { zone ->
            test("aggregate derivation equals the row walk in $zone, across both DST changes") {
                withSqlDatabase {
                    runTest {
                        (0 until CASES_PER_ZONE).forEach { seed ->
                            val user = "u-$seed"
                            sql.seedTestUser(user, timezone = zone)
                            val random = Random(seed * 31 + zone.hashCode())
                            val centre = if (seed % 2 == 0) springForward else fallBack
                            seedHistory(user, random, centre)
                            val nowMs = centre + random.nextLong(-3 * DAY_MS, 10 * DAY_MS)

                            withClue("zone=$zone seed=$seed now=$nowMs") {
                                deriveUserStats(sql, user, nowMs) shouldBe rowWalkDerivation(sql, user, nowMs)
                            }
                        }
                    }
                }
            }
        }
    })

private const val CASES_PER_ZONE = 40
private const val DAY_MS = 86_400_000L
private const val HOUR_MS = 3_600_000L

/** Random listening events (some deleted, some sub-second or negative), positions and completions near [centre]. */
private fun SqlTestDatabases.seedHistory(
    user: String,
    random: Random,
    centre: Long,
) {
    sql.transaction {
        repeat(random.nextInt(0, 80)) { i ->
            val ended = centre + random.nextLong(-40 * DAY_MS, 3 * DAY_MS)
            val length =
                when (random.nextInt(6)) {
                    0 -> random.nextLong(0, 1_000)

                    // sub-second: truncates to 0 whole seconds
                    1 -> -random.nextLong(1, 5_000)

                    // a clock-skewed span that ends before it starts
                    else -> random.nextLong(1_000, 3 * HOUR_MS)
                }
            sql.listeningEventsQueries.insert(
                id = "$user-e$i",
                user_id = user,
                book_id = "b${random.nextInt(12)}",
                start_position_ms = 0,
                end_position_ms = length,
                started_at = ended - length,
                ended_at = ended,
                playback_speed = 1.0,
                tz = "UTC",
                device_label = null,
                revision = 0,
                created_at = 0,
                updated_at = 0,
                deleted_at = if (random.nextInt(8) == 0) ended else null,
                client_op_id = null,
            )
        }
        repeat(random.nextInt(0, 4)) { i ->
            sql.bookReadsQueries.insert(
                id = "$user-r$i",
                user_id = user,
                book_id = "b$i",
                finished_at = centre + random.nextLong(-40 * DAY_MS, 3 * DAY_MS),
                source = "test",
                created_at = 0,
            )
        }
        repeat(random.nextInt(0, 4)) { i ->
            sql.playbackPositionsQueries.insert(
                id = "$user-p$i",
                user_id = user,
                book_id = "pb$i",
                position_ms = 1_000,
                last_played_at = centre + random.nextLong(-40 * DAY_MS, 3 * DAY_MS),
                finished = 0,
                playback_speed = 1.0,
                volume_boost_db = 0.0,
                measured_gain_db = null,
                finished_at = null,
                has_custom_speed = 0,
                has_custom_boost = 0,
                current_chapter_id = null,
                revision = 0,
                created_at = 0,
                updated_at = 0,
                deleted_at = null,
                client_op_id = null,
            )
        }
    }
}

/** The derivation as it was before it moved to SQL aggregates: every row loaded and walked. The oracle. */
private suspend fun rowWalkDerivation(
    sql: ListenUpDatabase,
    userId: String,
    nowMs: Long,
): UserStatsSyncPayload {
    val events = suspendTransaction(sql) { sql.listeningEventsQueries.selectForUserOrderedByEndedAt(userId).executeAsList() }
    val userTz: TimeZone = sql.homeTimeZone(userId)
    var totalAllTime = 0L
    val distinctBooks = mutableSetOf<String>()
    val listeningDays = ArrayList<LocalDate>(events.size)
    for (event in events) {
        totalAllTime += (event.ended_at - event.started_at) / 1_000L
        distinctBooks.add(event.book_id)
        listeningDays += Instant.fromEpochMilliseconds(event.ended_at).toLocalDateTime(userTz).date
    }
    val now = Instant.fromEpochMilliseconds(nowMs)
    val weekStart = StatsWindow.Week.startMs(now, userTz)
    val monthStart = StatsWindow.Month.startMs(now, userTz)
    var last7 = 0L
    var last30 = 0L
    for (event in events) {
        last7 += StatsWindow.secondsCounted(event.started_at, event.ended_at, weekStart)
        last30 += StatsWindow.secondsCounted(event.started_at, event.ended_at, monthStart)
    }
    val booksFinished =
        suspendTransaction(sql) {
            sql.bookReadsQueries
                .countForUser(userId)
                .executeAsOne()
                .toInt()
        }
    val streakDays = streakDayInstantsMs(sql, userId, events.map { it.ended_at }).map { localDayOf(it, userTz) }
    val streaks = StreakReducer.reduce(streakDays, now.toLocalDateTime(userTz).date)
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
