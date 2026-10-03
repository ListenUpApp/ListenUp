@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.domain.stats.StatsWindow
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone

/**
 * Pins the two invariants the window sum must hold so windowed totals can never disagree with the
 * all-time / streak reads that derive from the same `listening_events` primitive, nor with Home:
 *
 * 1. **Soft-deleted events contribute nothing.** `sumWallSecondsEndedSince` must filter `deleted_at IS
 *    NULL` like its sibling queries; otherwise a client-deleted event still inflates the leaderboard
 *    windows while the deleted-filtered backfill excludes it.
 * 2. **A span that ends inside the window counts whole** — the shared `StatsWindow` rule Home applies.
 *    (These windows used to clip a boundary-spanning span to its post-cutoff seconds; Home never did,
 *    so the two figures disagreed. One rule now holds everywhere.)
 */
class WindowSumCountingRuleTest :
    FunSpec({

        test("sumWallSecondsEndedSince excludes soft-deleted events") {
            withSqlDatabase {
                sql.seedTestUser("u1")
                // Live 60s span, ended after the window start.
                sql.insertEvent(id = "live", userId = "u1", startedAt = 1_000L, endedAt = 61_000L)
                // Soft-deleted 120s span, also ended after the window start — must NOT count.
                sql.insertEvent(id = "gone", userId = "u1", startedAt = 1_000L, endedAt = 121_000L, deletedAt = 500_000L)

                sql.listeningEventsQueries.sumWallSecondsEndedSince(userId = "u1", windowStartMs = 0L).executeAsOne() shouldBe 60L
            }
        }

        test("sumWallSecondsEndedSince counts a span that straddles the window start whole") {
            withSqlDatabase {
                sql.seedTestUser("u1")
                // Wall span 120s (40_000..160_000), window opens at 100_000 — it ended inside, so all 120s count.
                sql.insertEvent(id = "straddle", userId = "u1", startedAt = 40_000L, endedAt = 160_000L)
                // Ended before the window opened — nothing.
                sql.insertEvent(id = "before", userId = "u1", startedAt = 10_000L, endedAt = 99_999L)

                sql.listeningEventsQueries.sumWallSecondsEndedSince(userId = "u1", windowStartMs = 100_000L).executeAsOne() shouldBe 120L
            }
        }

        test("backfill window totals equal the SQL sum on the same straddling fixture") {
            withSqlDatabase {
                sql.seedTestUser("u1")
                val nowMs = 1_779_451_200_000L // 2026-05-22 12:00 UTC
                val clock = FixedClock(Instant.fromEpochMilliseconds(nowMs))
                val weekStart = StatsWindow.Week.startMs(clock.now(), TimeZone.UTC)
                // A span straddling the week start: 60s wall, ends inside the week, so all 60s count.
                sql.insertEvent(id = "edge", userId = "u1", startedAt = weekStart - 30_000L, endedAt = weekStart + 30_000L)

                val bus = ChangeBus()
                val registry = SyncRegistry()
                val statsRepo = UserStatsRepository(db = sql, bus = bus, registry = registry)
                val backfill = UserStatsBackfillService(sql = sql, userStatsRepo = statsRepo, clock = clock)

                runTest {
                    backfill.backfillFor("u1")
                    val stats = statsRepo.getForUser("u1").shouldNotBeNull()
                    val sqlSum =
                        sql.listeningEventsQueries.sumWallSecondsEndedSince(userId = "u1", windowStartMs = weekStart).executeAsOne()

                    // The in-memory derivation and the SQL query must agree …
                    stats.totalSecondsLast7Days shouldBe sqlSum
                    // … and both count the whole span.
                    stats.totalSecondsLast7Days shouldBe 60L
                    stats.totalSecondsAllTime shouldBe 60L
                }
            }
        }
    })

private fun ListenUpDatabase.insertEvent(
    id: String,
    userId: String,
    startedAt: Long,
    endedAt: Long,
    bookId: String = "b1",
    deletedAt: Long? = null,
) {
    transaction {
        listeningEventsQueries.insert(
            id = id,
            user_id = userId,
            book_id = bookId,
            start_position_ms = 0L,
            end_position_ms = endedAt - startedAt,
            started_at = startedAt,
            ended_at = endedAt,
            playback_speed = 1.0,
            tz = "UTC",
            device_label = null,
            revision = 0L,
            created_at = 0L,
            updated_at = 0L,
            deleted_at = deletedAt,
            client_op_id = null,
        )
    }
}
