@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.activity.ActivityType
import com.calypsan.listenup.api.sync.ListeningEventSyncPayload
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.PublicProfileRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.activityRecorder
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

class StatsRecorderListeningSessionClosedTest :
    FunSpec({

        val day0Ms = 1_779_451_200_000L // 2026-05-22 12:00:00 UTC
        val dayMs = 86_400_000L
        val clock = FixedClock(Instant.fromEpochMilliseconds(day0Ms))

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

        test("ListeningSessionClosed materializes totals, streak, and refreshes the projection in one call") {
            withSqlDatabase {
                sql.seedTestUser("u1")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val userStatsRepo = UserStatsRepository(db = sql, bus = bus, registry = registry)
                val publicProfileRepo = PublicProfileRepository(db = sql, bus = bus, registry = registry)
                val publicProfileMaintainer =
                    PublicProfileMaintainer(sql = sql, publicProfileRepo = publicProfileRepo, clock = clock)
                val recorder =
                    StatsRecorder(
                        sql = sql,
                        userStatsRepo = userStatsRepo,
                        bookReadsRepository = BookReadsRepository(db = sql),
                        publicProfileMaintainer = publicProfileMaintainer,
                        activityRecorder = activityRecorder(bus = bus),
                        statsBackfill = UserStatsBackfillService(sql = sql, userStatsRepo = userStatsRepo),
                        clock = clock,
                    )
                val eventRepo = ListeningEventRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())

                runTest {
                    val event = eventAt("evt-1", "book-1", endedAtMs = day0Ms, wallSeconds = 30L)
                    eventRepo.upsert(event, clientOpId = null, userId = "u1")
                    recorder.record(StatsEvent.ListeningSessionClosed(userId = "u1", span = event))

                    val stats = userStatsRepo.getForUser("u1").shouldNotBeNull()
                    stats.totalSecondsAllTime shouldBe 30L
                    stats.currentStreakDays shouldBe 1

                    val profile = publicProfileRepo.pullSince(userId = null, cursor = 0L, limit = 10).items.single()
                    profile.totalSecondsAllTime shouldBe 30L
                }
            }
        }

        test("event for a new book on same day increments booksStarted but not streak") {
            withSqlDatabase {
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val userStatsRepo = UserStatsRepository(db = sql, bus = bus, registry = registry)
                val eventRepo = ListeningEventRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                val recorder =
                    StatsRecorder(
                        sql = sql,
                        userStatsRepo = userStatsRepo,
                        bookReadsRepository = BookReadsRepository(db = sql),
                        publicProfileMaintainer =
                            PublicProfileMaintainer(
                                sql = sql,
                                publicProfileRepo = PublicProfileRepository(db = sql, bus = bus, registry = registry),
                                clock = clock,
                            ),
                        activityRecorder = activityRecorder(bus = bus),
                        statsBackfill = UserStatsBackfillService(sql = sql, userStatsRepo = userStatsRepo),
                        clock = clock,
                    )

                runTest {
                    val e1 = eventAt("evt-1", "book-1", endedAtMs = day0Ms, wallSeconds = 30L)
                    eventRepo.upsert(e1, clientOpId = null, userId = "u1")
                    recorder.record(StatsEvent.ListeningSessionClosed(userId = "u1", span = e1))

                    val e2 = eventAt("evt-2", "book-2", endedAtMs = day0Ms + 60_000L, wallSeconds = 30L)
                    eventRepo.upsert(e2, clientOpId = null, userId = "u1")
                    recorder.record(StatsEvent.ListeningSessionClosed(userId = "u1", span = e2))

                    val stats = userStatsRepo.getForUser("u1").shouldNotBeNull()
                    stats.booksStarted shouldBe 2
                    stats.currentStreakDays shouldBe 1
                }
            }
        }

        test("event after 3-day gap resets currentStreak to 1 but longestStreak stays at prior max") {
            withSqlDatabase {
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val userStatsRepo = UserStatsRepository(db = sql, bus = bus, registry = registry)
                val eventRepo = ListeningEventRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                // Re-derived streaks are as-of-now: the clock must reflect when the latest event is
                // recorded (day0+4), or the last event reads as "in the future" and current decays to 0.
                val nowClock = FixedClock(Instant.fromEpochMilliseconds(day0Ms + 4 * dayMs))
                val recorder =
                    StatsRecorder(
                        sql = sql,
                        userStatsRepo = userStatsRepo,
                        bookReadsRepository = BookReadsRepository(db = sql),
                        publicProfileMaintainer =
                            PublicProfileMaintainer(
                                sql = sql,
                                publicProfileRepo = PublicProfileRepository(db = sql, bus = bus, registry = registry),
                                clock = nowClock,
                            ),
                        activityRecorder = activityRecorder(bus = bus),
                        statsBackfill = UserStatsBackfillService(sql = sql, userStatsRepo = userStatsRepo),
                        clock = nowClock,
                    )

                runTest {
                    // Build a 2-day streak
                    val e1 = eventAt("evt-1", "book-1", endedAtMs = day0Ms, wallSeconds = 30L)
                    eventRepo.upsert(e1, clientOpId = null, userId = "u1")
                    recorder.record(StatsEvent.ListeningSessionClosed(userId = "u1", span = e1))

                    val e2 = eventAt("evt-2", "book-1", endedAtMs = day0Ms + dayMs, wallSeconds = 30L)
                    eventRepo.upsert(e2, clientOpId = null, userId = "u1")
                    recorder.record(StatsEvent.ListeningSessionClosed(userId = "u1", span = e2))

                    // 3-day gap — breaks the streak
                    val e3 = eventAt("evt-3", "book-1", endedAtMs = day0Ms + 4 * dayMs, wallSeconds = 30L)
                    eventRepo.upsert(e3, clientOpId = null, userId = "u1")
                    recorder.record(StatsEvent.ListeningSessionClosed(userId = "u1", span = e3))

                    val stats = userStatsRepo.getForUser("u1").shouldNotBeNull()
                    stats.currentStreakDays shouldBe 1
                    stats.longestStreakDays shouldBe 2
                }
            }
        }

        test("totalSecondsLast7Days sums only events within 7 days of the latest event") {
            withSqlDatabase {
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val userStatsRepo = UserStatsRepository(db = sql, bus = bus, registry = registry)
                val eventRepo = ListeningEventRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                val recorder =
                    StatsRecorder(
                        sql = sql,
                        userStatsRepo = userStatsRepo,
                        bookReadsRepository = BookReadsRepository(db = sql),
                        publicProfileMaintainer =
                            PublicProfileMaintainer(
                                sql = sql,
                                publicProfileRepo = PublicProfileRepository(db = sql, bus = bus, registry = registry),
                                clock = clock,
                            ),
                        activityRecorder = activityRecorder(bus = bus),
                        statsBackfill = UserStatsBackfillService(sql = sql, userStatsRepo = userStatsRepo),
                        clock = clock,
                    )

                runTest {
                    // Event 14 days ago (outside 7-day window)
                    val old = eventAt("evt-old", "book-1", endedAtMs = day0Ms - 14 * dayMs, wallSeconds = 100L)
                    eventRepo.upsert(old, clientOpId = null, userId = "u1")
                    recorder.record(StatsEvent.ListeningSessionClosed(userId = "u1", span = old))

                    // Events within 7 days of day0Ms (the "latest" event's endedAt)
                    val recent1 = eventAt("evt-recent1", "book-1", endedAtMs = day0Ms - 3 * dayMs, wallSeconds = 60L)
                    eventRepo.upsert(recent1, clientOpId = null, userId = "u1")
                    recorder.record(StatsEvent.ListeningSessionClosed(userId = "u1", span = recent1))

                    val recent2 = eventAt("evt-recent2", "book-1", endedAtMs = day0Ms, wallSeconds = 40L)
                    eventRepo.upsert(recent2, clientOpId = null, userId = "u1")
                    recorder.record(StatsEvent.ListeningSessionClosed(userId = "u1", span = recent2))

                    val stats = userStatsRepo.getForUser("u1").shouldNotBeNull()
                    // Only recent1 + recent2 should count for last 7 days
                    stats.totalSecondsLast7Days shouldBe 100L
                    stats.totalSecondsAllTime shouldBe 200L
                }
            }
        }

        test("streak reaching exactly 7 records one streak_milestone(7, days); a same-day no-op event adds no duplicate") {
            withSqlDatabase {
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val userStatsRepo = UserStatsRepository(db = sql, bus = bus, registry = registry)
                val eventRepo = ListeningEventRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                val activities = ActivityRepository(db = sql)
                // Re-derived streaks are as-of-now: pin the clock to the last day (day0+6) so the run of
                // seven days ends on "today" and the current streak reads 7.
                val nowClock = FixedClock(Instant.fromEpochMilliseconds(day0Ms + 6 * dayMs))
                val recorder =
                    StatsRecorder(
                        sql = sql,
                        userStatsRepo = userStatsRepo,
                        bookReadsRepository = BookReadsRepository(db = sql),
                        publicProfileMaintainer =
                            PublicProfileMaintainer(
                                sql = sql,
                                publicProfileRepo = PublicProfileRepository(db = sql, bus = bus, registry = registry),
                                clock = nowClock,
                            ),
                        activityRecorder = activityRecorder(bus = bus),
                        statsBackfill = UserStatsBackfillService(sql = sql, userStatsRepo = userStatsRepo),
                        clock = nowClock,
                    )

                runTest {
                    // Seven consecutive days ending on "today"; the final re-derive reads a 7-day streak,
                    // crossing the 7-day milestone exactly once.
                    repeat(7) { day ->
                        val e = eventAt("evt-$day", "book-1", endedAtMs = day0Ms + day * dayMs, wallSeconds = 30L)
                        eventRepo.upsert(e, clientOpId = null, userId = "u1")
                        recorder.record(StatsEvent.ListeningSessionClosed(userId = "u1", span = e))
                    }
                    userStatsRepo.getForUser("u1").shouldNotBeNull().currentStreakDays shouldBe 7

                    val afterStreak =
                        activities.page(before = null, limit = 50).filter { it.type == ActivityType.STREAK_MILESTONE }
                    afterStreak shouldHaveSize 1
                    afterStreak.single().milestoneValue shouldBe 7
                    afterStreak.single().milestoneUnit shouldBe "days"

                    // A same-day event (day 6 again) leaves the streak at 7 — no new milestone.
                    val sameDay = eventAt("evt-same", "book-1", endedAtMs = day0Ms + 6 * dayMs + 60_000L, wallSeconds = 30L)
                    eventRepo.upsert(sameDay, clientOpId = null, userId = "u1")
                    recorder.record(StatsEvent.ListeningSessionClosed(userId = "u1", span = sameDay))

                    activities
                        .page(before = null, limit = 50)
                        .filter { it.type == ActivityType.STREAK_MILESTONE } shouldHaveSize 1
                }
            }
        }

        test("total listening hours crossing 10 records one listening_milestone(10, hours)") {
            withSqlDatabase {
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val userStatsRepo = UserStatsRepository(db = sql, bus = bus, registry = registry)
                val eventRepo = ListeningEventRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                val activities = ActivityRepository(db = sql)
                val recorder =
                    StatsRecorder(
                        sql = sql,
                        userStatsRepo = userStatsRepo,
                        bookReadsRepository = BookReadsRepository(db = sql),
                        publicProfileMaintainer =
                            PublicProfileMaintainer(
                                sql = sql,
                                publicProfileRepo = PublicProfileRepository(db = sql, bus = bus, registry = registry),
                                clock = clock,
                            ),
                        activityRecorder = activityRecorder(bus = bus),
                        statsBackfill = UserStatsBackfillService(sql = sql, userStatsRepo = userStatsRepo),
                        clock = clock,
                    )

                runTest {
                    // A single 10-hour span carries the all-time total from 0 across the 10-hour mark.
                    val tenHoursSeconds = 10L * 3600L
                    val e = eventAt("evt-10h", "book-1", endedAtMs = day0Ms, wallSeconds = tenHoursSeconds)
                    eventRepo.upsert(e, clientOpId = null, userId = "u1")
                    recorder.record(StatsEvent.ListeningSessionClosed(userId = "u1", span = e))

                    userStatsRepo.getForUser("u1").shouldNotBeNull().totalSecondsAllTime shouldBe tenHoursSeconds

                    val milestones =
                        activities.page(before = null, limit = 50).filter { it.type == ActivityType.LISTENING_MILESTONE }
                    milestones shouldHaveSize 1
                    milestones.single().milestoneValue shouldBe 10
                    milestones.single().milestoneUnit shouldBe "hours"
                }
            }
        }

        test("ListeningSessionClosed delineates days in the user's home timezone, not the event's stored tz") {
            // Same instant construction as the backfill test: two events that in UTC land on
            // the same calendar day (June 10) but in America/New_York land on different days
            // (June 9 and June 10). The events' stored tz = "UTC" (ABS-import style).
            //
            // Under UTC:  same day → no streak increment (streak stays at 1 after second event)
            // Under NY:   consecutive days → streak becomes 2
            //
            // clock is set to 2026-06-11T12:00:00Z so "now" is after both events.

            // 2026-06-10T03:30:00Z — lands on June 9 in NY (23:30 EDT)
            val eventAMs = 1_781_062_200_000L
            // 2026-06-10T23:30:00Z — lands on June 10 in NY (19:30 EDT); still June 10 UTC
            val eventBMs = 1_781_134_200_000L

            val testClock = FixedClock(Instant.fromEpochMilliseconds(1_781_179_200_000L)) // 2026-06-11 12:00 UTC

            withSqlDatabase {
                sql.seedTestUser(userId = "u1", timezone = "America/New_York")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val userStatsRepo = UserStatsRepository(db = sql, bus = bus, registry = registry, clock = testClock)
                val eventRepo = ListeningEventRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                val recorder =
                    StatsRecorder(
                        sql = sql,
                        userStatsRepo = userStatsRepo,
                        bookReadsRepository = BookReadsRepository(db = sql),
                        publicProfileMaintainer =
                            PublicProfileMaintainer(
                                sql = sql,
                                publicProfileRepo = PublicProfileRepository(db = sql, bus = bus, registry = registry),
                                clock = testClock,
                            ),
                        activityRecorder = activityRecorder(bus = bus),
                        statsBackfill = UserStatsBackfillService(sql = sql, userStatsRepo = userStatsRepo),
                        clock = testClock,
                    )

                runTest {
                    val evtA = eventAt("ea", "book-1", endedAtMs = eventAMs, tz = "UTC")
                    eventRepo.upsert(evtA, clientOpId = null, userId = "u1")
                    recorder.record(StatsEvent.ListeningSessionClosed(userId = "u1", span = evtA))

                    val evtB = eventAt("eb", "book-1", endedAtMs = eventBMs, tz = "UTC")
                    eventRepo.upsert(evtB, clientOpId = null, userId = "u1")
                    recorder.record(StatsEvent.ListeningSessionClosed(userId = "u1", span = evtB))

                    val stats = userStatsRepo.getForUser("u1").shouldNotBeNull()
                    // NY delineation: June 9 → June 10 = consecutive → streak = 2
                    stats.currentStreakDays shouldBe 2
                    stats.longestStreakDays shouldBe 2
                }
            }
        }
    })
