@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.PublicProfileRepository
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
 * The server's windowed totals use the same window Home does: today plus the previous days back to
 * local midnight in the listener's home timezone (`users.timezone`, UTC when absent or malformed), with
 * a span counted whole when it ends inside the window. Before this, the server summed a rolling
 * `now - 168h` window and clipped boundary spans, so Home and the Leaderboard disagreed about the same
 * week.
 */
class UserStatsCalendarWindowTest :
    FunSpec({

        fun ms(iso: String): Long = Instant.parse(iso).toEpochMilliseconds()

        test("Simon's week: an Edmonton listener's week starts at local midnight six days ago, not 168h back") {
            withSqlDatabase {
                sql.seedTestUser("simon", timezone = "America/Edmonton")
                // now = 2026-10-02 10:20 MDT. Home's week starts 2026-09-26 00:00 MDT (06:00Z); the old
                // rolling cutoff was 2026-09-25T16:20Z.
                val now = ms("2026-10-02T16:20:00Z")
                // 27 min on Sep 25 at 14:00 MDT: inside the rolling 168h, outside the calendar week.
                sql.insertSpan(
                    "before-week",
                    userId = "simon",
                    startedAt = ms("2026-09-25T19:33:00Z"),
                    endedAt = ms("2026-09-25T20:00:00Z"),
                )
                // 40 min straddling local midnight Sep 26: ends inside the week, so it counts whole.
                sql.insertSpan(
                    "straddle",
                    userId = "simon",
                    startedAt = ms("2026-09-26T05:40:00Z"),
                    endedAt = ms("2026-09-26T06:20:00Z"),
                )
                // 5h 28m today.
                sql.insertSpan(
                    "today",
                    userId = "simon",
                    startedAt = ms("2026-10-02T09:52:00Z"),
                    endedAt = ms("2026-10-02T15:20:00Z"),
                )

                runTest {
                    val stats = deriveUserStats(sql, "simon", now)
                    // Home showed 6h 8m (368 min); the Leaderboard showed 6h 35m (395 min).
                    stats.totalSecondsLast7Days shouldBe 368 * 60L
                }
            }
        }

        test("the 30-day total starts at local midnight 29 days ago") {
            withSqlDatabase {
                sql.seedTestUser("u1", timezone = "America/Edmonton")
                val now = ms("2026-10-02T16:20:00Z")
                // Sep 2 14:00 MDT: inside rolling 30×24h (cutoff Sep 2 16:20Z), before Sep 3 00:00 MDT.
                sql.insertSpan("old", startedAt = ms("2026-09-02T19:00:00Z"), endedAt = ms("2026-09-02T20:00:00Z"))
                // Sep 3 00:30 MDT: inside.
                sql.insertSpan("in", startedAt = ms("2026-09-03T06:00:00Z"), endedAt = ms("2026-09-03T06:30:00Z"))

                runTest {
                    deriveUserStats(sql, "u1", now).totalSecondsLast30Days shouldBe 30 * 60L
                }
            }
        }

        test("a malformed home timezone falls back to UTC midnight") {
            withSqlDatabase {
                sql.seedTestUser("u1", timezone = "Not/AZone")
                val now = ms("2026-10-02T16:20:00Z")
                // 23:30Z on Sep 25: inside rolling 168h, before UTC midnight Sep 26.
                sql.insertSpan("before", startedAt = ms("2026-09-25T23:00:00Z"), endedAt = ms("2026-09-25T23:30:00Z"))
                sql.insertSpan("after", startedAt = ms("2026-09-26T00:10:00Z"), endedAt = ms("2026-09-26T00:30:00Z"))

                runTest {
                    deriveUserStats(sql, "u1", now).totalSecondsLast7Days shouldBe 20 * 60L
                }
            }
        }

        test("across the spring-forward transition the week still starts at local midnight") {
            withSqlDatabase {
                sql.seedTestUser("u1", timezone = "America/Edmonton")
                // 2026-03-10 12:00 MDT; DST began 2026-03-08. Week starts 2026-03-04 00:00 MST = 07:00Z.
                val now = ms("2026-03-10T18:00:00Z")
                // Mar 3 23:30 MST: outside.
                sql.insertSpan("before", startedAt = ms("2026-03-04T06:00:00Z"), endedAt = ms("2026-03-04T06:30:00Z"))
                // Mar 4 00:30 MST: inside.
                sql.insertSpan("after", startedAt = ms("2026-03-04T07:15:00Z"), endedAt = ms("2026-03-04T07:30:00Z"))

                runTest {
                    deriveUserStats(sql, "u1", now).totalSecondsLast7Days shouldBe 15 * 60L
                }
            }
        }

        test("the leaderboard projection's windowed time, books and streak share the calendar window") {
            withSqlDatabase {
                sql.seedTestUser("u1", timezone = "America/Edmonton")
                val now = ms("2026-10-02T16:20:00Z")
                val clock = FixedClock(Instant.fromEpochMilliseconds(now))
                // Sep 25 14:00 MDT — outside the week; Oct 2 2025 23:00 MDT — outside the 365-day year.
                sql.insertSpan("sep25", startedAt = ms("2026-09-25T19:00:00Z"), endedAt = ms("2026-09-25T20:00:00Z"))
                sql.insertSpan(
                    "last-year",
                    startedAt = ms("2025-10-03T04:00:00Z"),
                    endedAt = ms("2025-10-03T05:00:00Z"),
                )
                sql.insertSpan("today", startedAt = ms("2026-10-02T15:00:00Z"), endedAt = ms("2026-10-02T15:30:00Z"))
                sql.insertRead("read-sep25", bookId = "b-old", finishedAt = ms("2026-09-25T20:00:00Z"))

                val bus = ChangeBus()
                val statsRepo = UserStatsRepository(db = sql, bus = bus, registry = SyncRegistry(), clock = clock)
                val profileRepo = PublicProfileRepository(db = sql, bus = bus, registry = SyncRegistry(), clock = clock)
                val maintainer = PublicProfileMaintainer(sql = sql, publicProfileRepo = profileRepo, clock = clock)

                runTest {
                    statsRepo.upsert(deriveUserStats(sql, "u1", now), clientOpId = null, userId = "u1")
                    maintainer.refresh("u1")
                    val profile =
                        profileRepo
                            .pullSince(userId = null, cursor = 0L, limit = 10)
                            .items
                            .single { it.id == "u1" }
                            .shouldNotBeNull()

                    profile.totalSecondsLast7Days shouldBe 30 * 60L
                    profile.booksFinishedLast7Days shouldBe 0
                    profile.longestStreakLast7Days shouldBe 1
                    profile.totalSecondsLast365Days shouldBe 90 * 60L
                }
            }
        }
    })

internal fun ListenUpDatabase.insertSpan(
    id: String,
    startedAt: Long,
    endedAt: Long,
    userId: String = "u1",
    bookId: String = "b1",
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
            deleted_at = null,
            client_op_id = null,
        )
    }
}

internal fun ListenUpDatabase.insertRead(
    id: String,
    bookId: String,
    finishedAt: Long,
    userId: String = "u1",
) {
    transaction {
        bookReadsQueries.insert(
            id = id,
            user_id = userId,
            book_id = bookId,
            finished_at = finishedAt,
            source = BookReadSource.RECONCILE,
            created_at = finishedAt,
        )
    }
}
