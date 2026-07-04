@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.activity.ActivityType
import com.calypsan.listenup.api.sync.ListeningEventSyncPayload
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.PublicProfileRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.activityRecorder
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * Regression coverage for late-arriving listening events (offline-outbox replay landing an
 * older-dated event after a newer one already updated `user_stats`). Streak math is
 * path-dependent — unlike the as-of-now window sums, it never self-heals — so a late arrival
 * must leave `currentStreakDays` and `lastEventDate` untouched.
 */
class StatsRecorderStreakOrderingTest :
    FunSpec({

        val day0Ms = 1_779_451_200_000L // 2026-05-22 12:00:00 UTC
        val dayMs = 86_400_000L

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

        fun SqlTestDatabases.recorderWith(
            userStatsRepo: UserStatsRepository,
            testClock: FixedClock,
        ): StatsRecorder {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            return StatsRecorder(
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
        }

        test("late-arriving older event preserves streak and lastEventDate") {
            withSqlDatabase {
                val testClock = FixedClock(Instant.fromEpochMilliseconds(day0Ms + dayMs))
                val userStatsRepo = UserStatsRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                val eventRepo = ListeningEventRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                val recorder = recorderWith(userStatsRepo, testClock)

                runTest {
                    val e1 = eventAt("evt-1", "book-1", endedAtMs = day0Ms, wallSeconds = 30L)
                    eventRepo.upsert(e1, clientOpId = null, userId = "u1")
                    recorder.record(StatsEvent.ListeningSessionClosed(userId = "u1", span = e1))

                    val e2 = eventAt("evt-2", "book-1", endedAtMs = day0Ms + dayMs, wallSeconds = 30L)
                    eventRepo.upsert(e2, clientOpId = null, userId = "u1")
                    recorder.record(StatsEvent.ListeningSessionClosed(userId = "u1", span = e2))

                    // Late arrival: an older-dated event (offline-outbox replay) lands after the
                    // newer ones above.
                    val e3 = eventAt("evt-late", "book-1", endedAtMs = day0Ms - 3 * dayMs, wallSeconds = 30L)
                    eventRepo.upsert(e3, clientOpId = null, userId = "u1")
                    recorder.record(StatsEvent.ListeningSessionClosed(userId = "u1", span = e3))

                    val stats = userStatsRepo.getForUser("u1").shouldNotBeNull()
                    stats.currentStreakDays shouldBe 2
                    stats.longestStreakDays shouldBe 2
                    stats.lastEventDate shouldBe "2026-05-23"
                    stats.totalSecondsAllTime shouldBe 90L
                }
            }
        }

        test("same-date event with a different id leaves streak and lastEventDate unchanged") {
            withSqlDatabase {
                val testClock = FixedClock(Instant.fromEpochMilliseconds(day0Ms + dayMs))
                val userStatsRepo = UserStatsRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                val eventRepo = ListeningEventRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                val recorder = recorderWith(userStatsRepo, testClock)

                runTest {
                    val e1 = eventAt("evt-1", "book-1", endedAtMs = day0Ms, wallSeconds = 30L)
                    eventRepo.upsert(e1, clientOpId = null, userId = "u1")
                    recorder.record(StatsEvent.ListeningSessionClosed(userId = "u1", span = e1))

                    val e2 = eventAt("evt-2", "book-1", endedAtMs = day0Ms + dayMs, wallSeconds = 30L)
                    eventRepo.upsert(e2, clientOpId = null, userId = "u1")
                    recorder.record(StatsEvent.ListeningSessionClosed(userId = "u1", span = e2))

                    // A different event id landing on the same calendar day.
                    val e4 = eventAt("evt-same-day", "book-1", endedAtMs = day0Ms + dayMs + 60_000L, wallSeconds = 30L)
                    eventRepo.upsert(e4, clientOpId = null, userId = "u1")
                    recorder.record(StatsEvent.ListeningSessionClosed(userId = "u1", span = e4))

                    val stats = userStatsRepo.getForUser("u1").shouldNotBeNull()
                    stats.currentStreakDays shouldBe 2
                    stats.lastEventDate shouldBe "2026-05-23"
                }
            }
        }

        test("next-day event after a late arrival increments from the preserved state") {
            withSqlDatabase {
                val testClock = FixedClock(Instant.fromEpochMilliseconds(day0Ms + 2 * dayMs))
                val userStatsRepo = UserStatsRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                val eventRepo = ListeningEventRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                val recorder = recorderWith(userStatsRepo, testClock)

                runTest {
                    val e1 = eventAt("evt-1", "book-1", endedAtMs = day0Ms, wallSeconds = 30L)
                    eventRepo.upsert(e1, clientOpId = null, userId = "u1")
                    recorder.record(StatsEvent.ListeningSessionClosed(userId = "u1", span = e1))

                    val e2 = eventAt("evt-2", "book-1", endedAtMs = day0Ms + dayMs, wallSeconds = 30L)
                    eventRepo.upsert(e2, clientOpId = null, userId = "u1")
                    recorder.record(StatsEvent.ListeningSessionClosed(userId = "u1", span = e2))

                    val e3 = eventAt("evt-late", "book-1", endedAtMs = day0Ms - 3 * dayMs, wallSeconds = 30L)
                    eventRepo.upsert(e3, clientOpId = null, userId = "u1")
                    recorder.record(StatsEvent.ListeningSessionClosed(userId = "u1", span = e3))

                    val e5 = eventAt("evt-5", "book-1", endedAtMs = day0Ms + 2 * dayMs, wallSeconds = 30L)
                    eventRepo.upsert(e5, clientOpId = null, userId = "u1")
                    recorder.record(StatsEvent.ListeningSessionClosed(userId = "u1", span = e5))

                    val stats = userStatsRepo.getForUser("u1").shouldNotBeNull()
                    stats.currentStreakDays shouldBe 3
                    stats.longestStreakDays shouldBe 3
                    stats.lastEventDate shouldBe "2026-05-24"
                }
            }
        }

        test("late event does not re-fire or un-fire a streak milestone") {
            withSqlDatabase {
                val testClock = FixedClock(Instant.fromEpochMilliseconds(day0Ms + 6 * dayMs))
                val userStatsRepo = UserStatsRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                val eventRepo = ListeningEventRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                val recorder = recorderWith(userStatsRepo, testClock)

                runTest {
                    // Seven consecutive days → streak climbs 1..7, firing STREAK_MILESTONE(7).
                    repeat(7) { day ->
                        val e = eventAt("evt-$day", "book-1", endedAtMs = day0Ms + day * dayMs, wallSeconds = 30L)
                        eventRepo.upsert(e, clientOpId = null, userId = "u1")
                        recorder.record(StatsEvent.ListeningSessionClosed(userId = "u1", span = e))
                    }
                    userStatsRepo.getForUser("u1").shouldNotBeNull().currentStreakDays shouldBe 7

                    val beforeLate =
                        activities.page(before = null, limit = 50).filter { it.type == ActivityType.STREAK_MILESTONE }
                    beforeLate shouldHaveSize 1

                    // A late arrival (offline-outbox replay of a much older event) must not
                    // rewind the streak, and therefore must not re-fire or un-fire the milestone.
                    val late = eventAt("evt-late", "book-1", endedAtMs = day0Ms - 5 * dayMs, wallSeconds = 30L)
                    eventRepo.upsert(late, clientOpId = null, userId = "u1")
                    recorder.record(StatsEvent.ListeningSessionClosed(userId = "u1", span = late))

                    val stats = userStatsRepo.getForUser("u1").shouldNotBeNull()
                    stats.currentStreakDays shouldBe 7

                    activities
                        .page(before = null, limit = 50)
                        .filter { it.type == ActivityType.STREAK_MILESTONE } shouldHaveSize 1
                }
            }
        }
    })
