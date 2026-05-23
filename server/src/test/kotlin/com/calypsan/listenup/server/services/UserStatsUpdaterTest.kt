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

class UserStatsUpdaterTest :
    FunSpec({

        // A fixed instant: 2026-05-22 12:00:00 UTC
        val baseInstant = Instant.fromEpochMilliseconds(1_779_451_200_000L)
        val clock = FixedClock(baseInstant)

        // Helpers — each event is a 30-second span ending at [endedAtMs] in UTC.
        fun eventAt(
            id: String,
            bookId: String,
            endedAtMs: Long,
            wallSeconds: Long = 30L,
            tz: String = "UTC",
        ): ListeningEventSyncPayload =
            ListeningEventSyncPayload(
                id = id,
                bookId = bookId,
                startPositionMs = 0L,
                endPositionMs = wallSeconds * 1_000L,
                startedAt = endedAtMs - wallSeconds * 1_000L,
                endedAt = endedAtMs,
                playbackSpeed = 1.0f,
                tz = tz,
                deviceLabel = null,
                revision = 0L,
                updatedAt = 0L,
                createdAt = 0L,
                deletedAt = null,
            )

        // 2026-05-22 12:00:00 UTC in epoch ms
        val day0Ms = 1_779_451_200_000L
        val dayMs = 86_400_000L

        test("first-ever event for u1 creates a row with correct totals and streak = 1") {
            withInMemoryDatabase {
                val statsRepo = UserStatsRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                val eventRepo = ListeningEventRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                val updater = UserStatsUpdater(db = this, userStatsRepo = statsRepo, clock = clock)

                runTest {
                    val event = eventAt("evt-1", "book-1", endedAtMs = day0Ms, wallSeconds = 30L)
                    // Insert the event row first so hasOtherEventForBook can query it
                    eventRepo.upsert(event, clientOpId = null, userId = "u1")
                    updater.onListeningEvent(userId = "u1", event = event)

                    val stats = statsRepo.getForUser("u1").shouldNotBeNull()
                    stats.totalSecondsAllTime shouldBe 30L
                    stats.booksStarted shouldBe 1
                    stats.currentStreakDays shouldBe 1
                    stats.longestStreakDays shouldBe 1
                    stats.lastEventDate shouldBe "2026-05-22"
                }
            }
        }

        test("second event same book same day increments time but not booksStarted or streak") {
            withInMemoryDatabase {
                val statsRepo = UserStatsRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                val eventRepo = ListeningEventRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                val updater = UserStatsUpdater(db = this, userStatsRepo = statsRepo, clock = clock)

                runTest {
                    val e1 = eventAt("evt-1", "book-1", endedAtMs = day0Ms, wallSeconds = 30L)
                    eventRepo.upsert(e1, clientOpId = null, userId = "u1")
                    updater.onListeningEvent("u1", e1)

                    val e2 = eventAt("evt-2", "book-1", endedAtMs = day0Ms + 60_000L, wallSeconds = 45L)
                    eventRepo.upsert(e2, clientOpId = null, userId = "u1")
                    updater.onListeningEvent("u1", e2)

                    val stats = statsRepo.getForUser("u1").shouldNotBeNull()
                    stats.totalSecondsAllTime shouldBe 75L
                    stats.booksStarted shouldBe 1
                    stats.currentStreakDays shouldBe 1
                }
            }
        }

        test("event for a new book on same day increments booksStarted but not streak") {
            withInMemoryDatabase {
                val statsRepo = UserStatsRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                val eventRepo = ListeningEventRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                val updater = UserStatsUpdater(db = this, userStatsRepo = statsRepo, clock = clock)

                runTest {
                    val e1 = eventAt("evt-1", "book-1", endedAtMs = day0Ms, wallSeconds = 30L)
                    eventRepo.upsert(e1, clientOpId = null, userId = "u1")
                    updater.onListeningEvent("u1", e1)

                    val e2 = eventAt("evt-2", "book-2", endedAtMs = day0Ms + 60_000L, wallSeconds = 30L)
                    eventRepo.upsert(e2, clientOpId = null, userId = "u1")
                    updater.onListeningEvent("u1", e2)

                    val stats = statsRepo.getForUser("u1").shouldNotBeNull()
                    stats.booksStarted shouldBe 2
                    stats.currentStreakDays shouldBe 1
                }
            }
        }

        test("event on next day increments streak to 2") {
            withInMemoryDatabase {
                val statsRepo = UserStatsRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                val eventRepo = ListeningEventRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                val updater = UserStatsUpdater(db = this, userStatsRepo = statsRepo, clock = clock)

                runTest {
                    val e1 = eventAt("evt-1", "book-1", endedAtMs = day0Ms, wallSeconds = 30L)
                    eventRepo.upsert(e1, clientOpId = null, userId = "u1")
                    updater.onListeningEvent("u1", e1)

                    val e2 = eventAt("evt-2", "book-1", endedAtMs = day0Ms + dayMs, wallSeconds = 30L)
                    eventRepo.upsert(e2, clientOpId = null, userId = "u1")
                    updater.onListeningEvent("u1", e2)

                    val stats = statsRepo.getForUser("u1").shouldNotBeNull()
                    stats.currentStreakDays shouldBe 2
                    stats.longestStreakDays shouldBe 2
                }
            }
        }

        test("event after 3-day gap resets currentStreak to 1 but longestStreak stays at prior max") {
            withInMemoryDatabase {
                val statsRepo = UserStatsRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                val eventRepo = ListeningEventRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                val updater = UserStatsUpdater(db = this, userStatsRepo = statsRepo, clock = clock)

                runTest {
                    // Build a 2-day streak
                    val e1 = eventAt("evt-1", "book-1", endedAtMs = day0Ms, wallSeconds = 30L)
                    eventRepo.upsert(e1, clientOpId = null, userId = "u1")
                    updater.onListeningEvent("u1", e1)

                    val e2 = eventAt("evt-2", "book-1", endedAtMs = day0Ms + dayMs, wallSeconds = 30L)
                    eventRepo.upsert(e2, clientOpId = null, userId = "u1")
                    updater.onListeningEvent("u1", e2)

                    // 3-day gap — breaks the streak
                    val e3 = eventAt("evt-3", "book-1", endedAtMs = day0Ms + 4 * dayMs, wallSeconds = 30L)
                    eventRepo.upsert(e3, clientOpId = null, userId = "u1")
                    updater.onListeningEvent("u1", e3)

                    val stats = statsRepo.getForUser("u1").shouldNotBeNull()
                    stats.currentStreakDays shouldBe 1
                    stats.longestStreakDays shouldBe 2
                }
            }
        }

        test("totalSecondsLast7Days sums only events within 7 days of the latest event") {
            withInMemoryDatabase {
                val statsRepo = UserStatsRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                val eventRepo = ListeningEventRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                val updater = UserStatsUpdater(db = this, userStatsRepo = statsRepo, clock = clock)

                runTest {
                    // Event 14 days ago (outside 7-day window)
                    val old = eventAt("evt-old", "book-1", endedAtMs = day0Ms - 14 * dayMs, wallSeconds = 100L)
                    eventRepo.upsert(old, clientOpId = null, userId = "u1")
                    updater.onListeningEvent("u1", old)

                    // Events within 7 days of day0Ms (the "latest" event's endedAt)
                    val recent1 = eventAt("evt-recent1", "book-1", endedAtMs = day0Ms - 3 * dayMs, wallSeconds = 60L)
                    eventRepo.upsert(recent1, clientOpId = null, userId = "u1")
                    updater.onListeningEvent("u1", recent1)

                    val recent2 = eventAt("evt-recent2", "book-1", endedAtMs = day0Ms, wallSeconds = 40L)
                    eventRepo.upsert(recent2, clientOpId = null, userId = "u1")
                    updater.onListeningEvent("u1", recent2)

                    val stats = statsRepo.getForUser("u1").shouldNotBeNull()
                    // Only recent1 + recent2 should count for last 7 days
                    stats.totalSecondsLast7Days shouldBe 100L
                    stats.totalSecondsAllTime shouldBe 200L
                }
            }
        }

        test("onPositionFinishedFlip increments booksFinished each call") {
            withInMemoryDatabase {
                val statsRepo = UserStatsRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                val updater = UserStatsUpdater(db = this, userStatsRepo = statsRepo, clock = clock)

                runTest {
                    updater.onPositionFinishedFlip("u1")
                    statsRepo.getForUser("u1").shouldNotBeNull().booksFinished shouldBe 1

                    updater.onPositionFinishedFlip("u1")
                    statsRepo.getForUser("u1").shouldNotBeNull().booksFinished shouldBe 2
                }
            }
        }
    })
