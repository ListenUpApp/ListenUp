@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.ListeningEventSyncPayload
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.withInMemoryDatabase
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
            withInMemoryDatabase {
                val db = this
                val clock = FixedClock(Instant.fromEpochMilliseconds(nowMs))
                val bus = ChangeBus()
                val statsRepo = UserStatsRepository(db = db, bus = bus, registry = SyncRegistry(), clock = clock)
                val eventRepo = ListeningEventRepository(db = db, bus = ChangeBus(), registry = SyncRegistry())
                val positionRepo = PlaybackPositionRepository(db = db, bus = ChangeBus(), registry = SyncRegistry())
                val backfillService = UserStatsBackfillService(db = db, userStatsRepo = statsRepo, clock = clock)

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
                    // Streak: events processed in endedAt order.
                    // Dates (UTC): -35d=Apr-17, -33d=Apr-19, -32d=Apr-20, -31d=Apr-21,
                    //              -29d=Apr-23, -20d=May-02, -10d=May-12, -5d=May-17,
                    //              -3d=May-19, -2d=May-20
                    // Apr-19→Apr-20→Apr-21 forms a 3-day streak (longestStreak = 3).
                    // Last two events May-19→May-20 form a 2-day streak (currentStreak = 2).
                    stats.currentStreakDays shouldBe 2
                    stats.longestStreakDays shouldBe 3
                }
            }
        }

        test("backfillFor replaces a stale pre-existing stats row with the correct rebuild") {
            withInMemoryDatabase {
                val db = this
                val clock = FixedClock(Instant.fromEpochMilliseconds(nowMs))
                val bus = ChangeBus()
                val statsRepo = UserStatsRepository(db = db, bus = bus, registry = SyncRegistry(), clock = clock)
                val eventRepo = ListeningEventRepository(db = db, bus = ChangeBus(), registry = SyncRegistry())
                val backfillService = UserStatsBackfillService(db = db, userStatsRepo = statsRepo, clock = clock)

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
    })
