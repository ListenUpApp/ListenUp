@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

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

/**
 * Pins the two invariants the rolling-window sum must hold so windowed totals can never disagree
 * with the all-time / streak reads that derive from the same `listening_events` primitive:
 *
 * 1. **Soft-deleted events contribute nothing.** `sumWallSecondsSince` must filter `deleted_at IS
 *    NULL` like its sibling queries; otherwise a client-deleted event still inflates the leaderboard
 *    windows while the deleted-filtered backfill excludes it.
 * 2. **Boundary-spanning sessions are clipped, not counted whole.** A span that started before the
 *    window cutoff but ended inside it contributes only its post-cutoff seconds — `MAX(started_at,
 *    cutoffMs)` — not the full wall-clock span.
 */
class WindowSumSoftDeleteClipTest :
    FunSpec({

        test("sumWallSecondsSince excludes soft-deleted events") {
            withSqlDatabase {
                sql.seedTestUser("u1")
                // Live 60s span, ended after the cutoff.
                sql.insertEvent(id = "live", userId = "u1", startedAt = 1_000L, endedAt = 61_000L)
                // Soft-deleted 120s span, also ended after the cutoff — must NOT count.
                sql.insertEvent(id = "gone", userId = "u1", startedAt = 1_000L, endedAt = 121_000L, deletedAt = 500_000L)

                sql.listeningEventsQueries.sumWallSecondsSince(userId = "u1", cutoffMs = 0L).executeAsOne() shouldBe 60L
            }
        }

        test("sumWallSecondsSince clips a span that straddles the cutoff to its post-cutoff seconds") {
            withSqlDatabase {
                sql.seedTestUser("u1")
                // Total wall span 120s (40_000..160_000); cutoff at 100_000 → only 60s is post-cutoff.
                sql.insertEvent(id = "straddle", userId = "u1", startedAt = 40_000L, endedAt = 160_000L)

                sql.listeningEventsQueries.sumWallSecondsSince(userId = "u1", cutoffMs = 100_000L).executeAsOne() shouldBe 60L
            }
        }

        test("backfill window totals equal the clipped SQL sum on the same straddling fixture") {
            withSqlDatabase {
                sql.seedTestUser("u1")
                val nowMs = 1_779_451_200_000L
                val dayMs = 86_400_000L
                val cutoff7 = nowMs - 7 * dayMs
                // A span straddling the 7-day cutoff: 60s wall, only the 30s after cutoff7 is in-window.
                sql.insertEvent(id = "edge", userId = "u1", startedAt = cutoff7 - 30_000L, endedAt = cutoff7 + 30_000L)

                val bus = ChangeBus()
                val registry = SyncRegistry()
                val statsRepo = UserStatsRepository(db = sql, bus = bus, registry = registry)
                val backfill =
                    UserStatsBackfillService(
                        sql = sql,
                        userStatsRepo = statsRepo,
                        clock = FixedClock(Instant.fromEpochMilliseconds(nowMs)),
                    )

                runTest {
                    backfill.backfillFor("u1")
                    val stats = statsRepo.getForUser("u1").shouldNotBeNull()
                    val sqlSum = sql.listeningEventsQueries.sumWallSecondsSince(userId = "u1", cutoffMs = cutoff7).executeAsOne()

                    // The in-memory backfill loop and the SQL query must agree …
                    stats.totalSecondsLast7Days shouldBe sqlSum
                    // … and both must reflect only the post-cutoff 30s, not the whole 60s span.
                    stats.totalSecondsLast7Days shouldBe 30L
                    // All-time always counts the full span.
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
