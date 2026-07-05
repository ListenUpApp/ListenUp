@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.ListeningEventSyncPayload
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * End-to-end verification of [UserStatsBackfillService].
 *
 * Seeds raw `listening_events` and a `playback_positions` (finished=true) row,
 * runs backfill, and asserts the materialized `user_stats` row reflects the
 * full history correctly.
 *
 * A second case verifies that backfill **replaces** a pre-existing stale row,
 * making the operation idempotent.
 */
class UserStatsBackfillServiceTest :
    FunSpec({

        // "now" for the test: 2026-05-22 12:00:00 UTC
        val nowMs = 1_779_451_200_000L
        val dayMs = 86_400_000L

        // Helper: create a listening event with the given endedAt and wallSeconds.
        fun event(
            id: String,
            bookId: String,
            endedAtMs: Long,
            wallSeconds: Long,
        ): ListeningEventSyncPayload =
            ListeningEventSyncPayload(
                id = id,
                bookId = bookId,
                startPositionMs = 0L,
                endPositionMs = wallSeconds * 1_000L,
                startedAt = endedAtMs - wallSeconds * 1_000L,
                endedAt = endedAtMs,
                playbackSpeed = 1.0f,
                tz = "UTC",
                deviceLabel = null,
                revision = 0L,
                updatedAt = 0L,
                createdAt = 0L,
                deletedAt = null,
            )

        test("backfillFor rebuilds user_stats from raw events and finished positions") {
            withSqlDatabase {
                val clock = FixedClock(Instant.fromEpochMilliseconds(nowMs))
                val bus = ChangeBus()
                val statsRepo = UserStatsRepository(db = sql, bus = bus, registry = SyncRegistry(), clock = clock)
                val eventRepo = ListeningEventRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                val positionRepo = PlaybackPositionRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                val backfillService = UserStatsBackfillService(sql = sql, userStatsRepo = statsRepo, clock = clock)

                runTest {
                    // === Listening events ===
                    // 3 within last 7 days → totalSecondsLast7Days = 3 * 60 = 180s
                    // 3 more within 8-30 days → totalSecondsLast30Days = 6 * 60 = 360s
                    // 4 older than 30 days → only count in allTime
                    // Total events: 10; allTime = 10 * 60 = 600s
                    // 2 distinct books used across all events (book-a and book-b)
                    // 4 distinct dates → streak depends on order:
                    //   day-35, day-29, day-5, day-2 (not consecutive) → longestStreak = 1, currentStreak depends on last date

                    // Events for book-a (7 events) and book-b (3 events)
                    val events =
                        listOf(
                            // 4 older than 30 days (outside last30 window)
                            event("e1", "book-a", endedAtMs = nowMs - 35 * dayMs, wallSeconds = 60L),
                            event("e2", "book-a", endedAtMs = nowMs - 33 * dayMs, wallSeconds = 60L),
                            event("e3", "book-b", endedAtMs = nowMs - 32 * dayMs, wallSeconds = 60L),
                            event("e4", "book-b", endedAtMs = nowMs - 31 * dayMs, wallSeconds = 60L),
                            // 2 within 8-30 days (inside last30, outside last7)
                            event("e5", "book-a", endedAtMs = nowMs - 29 * dayMs, wallSeconds = 60L),
                            event("e6", "book-a", endedAtMs = nowMs - 20 * dayMs, wallSeconds = 60L),
                            // 1 more in 8-30 day range
                            event("e7", "book-b", endedAtMs = nowMs - 10 * dayMs, wallSeconds = 60L),
                            // 3 within last 7 days
                            event("e8", "book-a", endedAtMs = nowMs - 5 * dayMs, wallSeconds = 60L),
                            event("e9", "book-a", endedAtMs = nowMs - 3 * dayMs, wallSeconds = 60L),
                            event("e10", "book-b", endedAtMs = nowMs - 2 * dayMs, wallSeconds = 60L),
                        )
                    events.forEach { eventRepo.upsert(it, clientOpId = null, userId = "u1") }

                    // 1 finished position for book-a
                    positionRepo.recordPosition(
                        userId = "u1",
                        bookId = "book-a",
                        positionMs = 0L,
                        lastPlayedAt = nowMs - dayMs,
                        finished = true,
                        playbackSpeed = 1.0f,
                        currentChapterId = null,
                    )

                    // Run backfill
                    backfillService.backfillFor("u1")

                    val stats = statsRepo.getForUser("u1").shouldNotBeNull()

                    // Total: 10 events × 60s = 600s
                    stats.totalSecondsAllTime shouldBe 600L
                    // Last 7 days: events e8, e9, e10 = 3 × 60 = 180s
                    stats.totalSecondsLast7Days shouldBe 180L
                    // Last 30 days: events e5 through e10 = 6 × 60... wait:
                    // e5 is 29d ago, e6 is 20d ago, e7 is 10d ago, e8 is 5d ago, e9 is 3d ago, e10 is 2d ago
                    // That's 6 events × 60 = 360s
                    stats.totalSecondsLast30Days shouldBe 360L
                    // 2 distinct books
                    stats.booksStarted shouldBe 2
                    // 1 finished position
                    stats.booksFinished shouldBe 1
                    // Streak day-set = event days UNION the finished position's last-played day.
                    // Event dates (UTC): -35d=Apr-17, -33d=Apr-19, -32d=Apr-20, -31d=Apr-21,
                    //              -29d=Apr-23, -20d=May-02, -10d=May-12, -5d=May-17,
                    //              -3d=May-19, -2d=May-20; finished position last-played = -1d=May-21.
                    // Apr-19→Apr-20→Apr-21 forms a 3-day run (longestStreak = 3).
                    // With the position counted, May-19→May-20→May-21 is also a 3-day run ending
                    // yesterday (today = May-22) → currentStreak = 3.
                    stats.currentStreakDays shouldBe 3
                    stats.longestStreakDays shouldBe 3
                }
            }
        }

        test("backfill: an old-only import yields currentStreak 0 but keeps longest + total") {
            withSqlDatabase {
                val clock = FixedClock(Instant.fromEpochMilliseconds(nowMs))
                val bus = ChangeBus()
                val statsRepo = UserStatsRepository(db = sql, bus = bus, registry = SyncRegistry(), clock = clock)
                val eventRepo = ListeningEventRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                val backfillService = UserStatsBackfillService(sql = sql, userStatsRepo = statsRepo, clock = clock)

                runTest {
                    // 3 consecutive days, all ~90 days before "now" (well outside today/yesterday window).
                    // day-90, day-89, day-88 → consecutive 3-day run → longestStreak = 3.
                    // Last event is day-88 ago — neither today nor yesterday → currentStreak should lapse to 0.
                    val base = nowMs - 90 * dayMs
                    listOf(
                        event("e1", "book-a", endedAtMs = base, wallSeconds = 60L),
                        event("e2", "book-a", endedAtMs = base + dayMs, wallSeconds = 60L),
                        event("e3", "book-a", endedAtMs = base + 2 * dayMs, wallSeconds = 60L),
                    ).forEach { eventRepo.upsert(it, clientOpId = null, userId = "u1") }

                    backfillService.backfillFor("u1")

                    val stats = statsRepo.getForUser("u1").shouldNotBeNull()
                    stats.currentStreakDays shouldBe 0
                    stats.longestStreakDays shouldBe 3
                    stats.totalSecondsAllTime shouldBe 180L
                }
            }
        }

        test("backfill: events ending today keep the current streak") {
            withSqlDatabase {
                val clock = FixedClock(Instant.fromEpochMilliseconds(nowMs))
                val bus = ChangeBus()
                val statsRepo = UserStatsRepository(db = sql, bus = bus, registry = SyncRegistry(), clock = clock)
                val eventRepo = ListeningEventRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                val backfillService = UserStatsBackfillService(sql = sql, userStatsRepo = statsRepo, clock = clock)

                runTest {
                    // 3 consecutive days ending today (nowMs is 2026-05-22 12:00 UTC).
                    // day-2, day-1, today → consecutive → currentStreak = 3.
                    listOf(
                        event("e1", "book-a", endedAtMs = nowMs - 2 * dayMs, wallSeconds = 60L),
                        event("e2", "book-a", endedAtMs = nowMs - dayMs, wallSeconds = 60L),
                        event("e3", "book-a", endedAtMs = nowMs, wallSeconds = 60L),
                    ).forEach { eventRepo.upsert(it, clientOpId = null, userId = "u1") }

                    backfillService.backfillFor("u1")

                    val stats = statsRepo.getForUser("u1").shouldNotBeNull()
                    stats.currentStreakDays shouldBe 3
                    stats.longestStreakDays shouldBe 3
                }
            }
        }

        test("backfillFor replaces a stale pre-existing stats row with the correct rebuild") {
            withSqlDatabase {
                val clock = FixedClock(Instant.fromEpochMilliseconds(nowMs))
                val bus = ChangeBus()
                val statsRepo = UserStatsRepository(db = sql, bus = bus, registry = SyncRegistry(), clock = clock)
                val eventRepo = ListeningEventRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                val backfillService = UserStatsBackfillService(sql = sql, userStatsRepo = statsRepo, clock = clock)

                runTest {
                    // Seed one event: 1 day ago, 120 seconds
                    eventRepo.upsert(
                        event("e1", "book-a", endedAtMs = nowMs - dayMs, wallSeconds = 120L),
                        clientOpId = null,
                        userId = "u1",
                    )

                    // Pre-seed a stats row with garbage values
                    statsRepo.upsert(
                        com.calypsan.listenup.api.sync.UserStatsSyncPayload(
                            id = "u1",
                            totalSecondsAllTime = 999_999L,
                            totalSecondsLast7Days = 888_888L,
                            totalSecondsLast30Days = 777_777L,
                            booksStarted = 42,
                            booksFinished = 99,
                            currentStreakDays = 100,
                            longestStreakDays = 200,
                            lastEventDate = "1970-01-01",
                            revision = 0L,
                            updatedAt = 0L,
                            createdAt = 0L,
                            deletedAt = null,
                        ),
                        clientOpId = null,
                        userId = "u1",
                    )

                    // Run backfill — should replace the garbage row
                    backfillService.backfillFor("u1")

                    val stats = statsRepo.getForUser("u1").shouldNotBeNull()
                    // Only 1 event with 120s
                    stats.totalSecondsAllTime shouldBe 120L
                    stats.totalSecondsLast7Days shouldBe 120L
                    stats.totalSecondsLast30Days shouldBe 120L
                    stats.booksStarted shouldBe 1
                    stats.booksFinished shouldBe 0
                }
            }
        }

        test("streak counts days with only imported progress + finishes, not just listening_events") {
            // ABS import writes mediaProgress → playback_positions + book_reads, but keeps
            // playbackSessions (→ listening_events) sparsely. A day the user demonstrably listened
            // (progress advanced / book finished) with no session row must still count toward the
            // streak. Here there are NO listening_events at all: yesterday is covered by a finished
            // position, today by a book_reads completion. Both must count → currentStreak = 2.
            withSqlDatabase {
                val clock = FixedClock(Instant.fromEpochMilliseconds(nowMs))
                val statsRepo = UserStatsRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry(), clock = clock)
                val positionRepo = PlaybackPositionRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                val backfillService = UserStatsBackfillService(sql = sql, userStatsRepo = statsRepo, clock = clock)

                runTest {
                    // Yesterday: a finished position (imported mediaProgress), no session row.
                    positionRepo.recordPosition(
                        userId = "u1",
                        bookId = "book-a",
                        positionMs = 0L,
                        lastPlayedAt = nowMs - dayMs,
                        finished = true,
                        playbackSpeed = 1.0f,
                        currentChapterId = null,
                    )
                    // Today: a book_reads completion for a different book, no session or position.
                    sql.bookReadsQueries.insert(
                        id = "br-today",
                        user_id = "u1",
                        book_id = "book-b",
                        finished_at = nowMs,
                        source = "import",
                        created_at = nowMs,
                    )

                    backfillService.backfillFor("u1")

                    val stats = statsRepo.getForUser("u1").shouldNotBeNull()
                    // yesterday (position) → today (book_read) = consecutive 2-day run ending today.
                    stats.currentStreakDays shouldBe 2
                    stats.longestStreakDays shouldBe 2
                }
            }
        }

        test("backfill delineates streak days in the user's home timezone, not per-event tz") {
            // Event instants chosen so UTC and America/New_York (UTC-4 EDT) yield DIFFERENT streak
            // results, proving the per-event tz field is ignored.
            //
            // Both events' stored tz = "UTC" (mimicking ABS imports).
            //
            // Event A: 2026-06-10T03:30:00Z  →  UTC day: June 10  |  NY day: June  9 (23:30 EDT)
            // Event B: 2026-06-10T23:30:00Z  →  UTC day: June 10  |  NY day: June 10 (19:30 EDT)
            //
            // Under UTC:  both events land on June 10 → same day → streak stays at 1
            // Under NY:   June 9 then June 10 → consecutive → streak becomes 2
            //
            // We set user.timezone = "America/New_York" → expect streak 2.
            // If the code still reads event.tz ("UTC") the streak stays 1 → test fails (RED).

            // 2026-06-10T03:30:00Z in epoch-millis
            val eventAMs = 1_781_062_200_000L // 2026-06-10 03:30:00 UTC → NY: 2026-06-09 23:30 EDT
            // 2026-06-10T23:30:00Z in epoch-millis (same UTC calendar day, different NY day)
            val eventBMs = 1_781_134_200_000L // 2026-06-10 23:30:00 UTC → NY: 2026-06-10 19:30 EDT

            // "now" = 2026-06-11T12:00:00Z — after both events
            val testNowMs = 1_781_179_200_000L

            withSqlDatabase {
                sql.seedTestUser(userId = "u1", timezone = "America/New_York")
                val clock = FixedClock(Instant.fromEpochMilliseconds(testNowMs))
                val statsRepo = UserStatsRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry(), clock = clock)
                val eventRepo = ListeningEventRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                val backfillService = UserStatsBackfillService(sql = sql, userStatsRepo = statsRepo, clock = clock)

                runTest {
                    // Both events store tz = "UTC" (like an ABS import) — the math must IGNORE this
                    eventRepo.upsert(
                        event("ea", "book-a", endedAtMs = eventAMs, wallSeconds = 60L),
                        clientOpId = null,
                        userId = "u1",
                    )
                    eventRepo.upsert(
                        event("eb", "book-a", endedAtMs = eventBMs, wallSeconds = 60L),
                        clientOpId = null,
                        userId = "u1",
                    )

                    backfillService.backfillFor("u1")

                    val stats = statsRepo.getForUser("u1").shouldNotBeNull()
                    // NY delineation: June 9 → June 10 = consecutive days → streak = 2
                    stats.currentStreakDays shouldBe 2
                    stats.longestStreakDays shouldBe 2
                    // lastEventDate should reflect June 10 in NY tz
                    stats.lastEventDate shouldBe "2026-06-10"
                }
            }
        }

        test("backfill falls back to UTC without crashing when user.timezone is malformed") {
            // 2026-06-10T03:30:00Z
            val eventAMs = 1_781_062_200_000L
            // 2026-06-10T23:30:00Z (same UTC day)
            val eventBMs = 1_781_134_200_000L
            val testNowMs = 1_781_179_200_000L

            withSqlDatabase {
                sql.seedTestUser(userId = "u1", timezone = "Not/AZone")
                val clock = FixedClock(Instant.fromEpochMilliseconds(testNowMs))
                val statsRepo = UserStatsRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry(), clock = clock)
                val eventRepo = ListeningEventRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                val backfillService = UserStatsBackfillService(sql = sql, userStatsRepo = statsRepo, clock = clock)

                runTest {
                    eventRepo.upsert(
                        event("ea", "book-a", endedAtMs = eventAMs, wallSeconds = 60L),
                        clientOpId = null,
                        userId = "u1",
                    )
                    eventRepo.upsert(
                        event("eb", "book-a", endedAtMs = eventBMs, wallSeconds = 60L),
                        clientOpId = null,
                        userId = "u1",
                    )

                    // Must not throw even with a malformed timezone
                    backfillService.backfillFor("u1")

                    val stats = statsRepo.getForUser("u1").shouldNotBeNull()
                    // Falls back to UTC: both events on June 10 UTC → same day → streak = 1
                    stats.currentStreakDays shouldBe 1
                    stats.lastEventDate shouldBe "2026-06-10"
                }
            }
        }
    })
