@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.activity.ActivityType
import com.calypsan.listenup.api.sync.ListeningEventSyncPayload
import com.calypsan.listenup.api.sync.UserStatsSyncPayload
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.PublicProfileRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.activityRecorder
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Instant
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/**
 * Pins the milestone-crossing semantics [StatsRecorder] must uphold: every threshold crossed
 * forward between the stored base row and a re-derive fires exactly once, a decrease onto a
 * milestone value fires nothing, and two racing cascades for the same user never double-fire.
 */
class StatsRecorderMilestoneEmissionTest :
    FunSpec({

        val day0Ms = 1_779_451_200_000L
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
            activities: ActivityRepository,
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

        fun emptyUserStats(
            userId: String,
            currentStreakDays: Int,
        ): UserStatsSyncPayload =
            UserStatsSyncPayload(
                id = userId,
                totalSecondsAllTime = 0L,
                totalSecondsLast7Days = 0L,
                totalSecondsLast30Days = 0L,
                booksStarted = 0,
                booksFinished = 0,
                currentStreakDays = currentStreakDays,
                longestStreakDays = currentStreakDays,
                lastEventDate = null,
                revision = 0L,
                updatedAt = 0L,
                createdAt = 0L,
                deletedAt = null,
            )

        test("multi-threshold hours jump emits every crossed milestone") {
            withSqlDatabase {
                sql.seedTestUser("u1")
                val testClock = FixedClock(Instant.fromEpochMilliseconds(day0Ms))
                val userStatsRepo = UserStatsRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                val eventRepo = ListeningEventRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                val activities = ActivityRepository(db = sql)
                val recorder = recorderWith(userStatsRepo, activities, testClock)

                runTest {
                    val e1 = eventAt("e1", "b1", endedAtMs = day0Ms, wallSeconds = 55L * 3600L)
                    eventRepo.upsert(e1, clientOpId = null, userId = "u1")
                    recorder.record(StatsEvent.ListeningSessionClosed(userId = "u1", span = e1))

                    val milestones =
                        activities
                            .page(before = null, limit = 100)
                            .filter { it.type == ActivityType.LISTENING_MILESTONE }
                            .sortedBy { it.milestoneValue }

                    milestones shouldHaveSize 2
                    milestones.map { it.milestoneValue } shouldBe listOf(10, 50)
                }
            }
        }

        test("streak jump over a milestone emits the skipped value") {
            withSqlDatabase {
                sql.seedTestUser("u1")
                val testClock = FixedClock(Instant.fromEpochMilliseconds(day0Ms + 7 * dayMs))
                val userStatsRepo = UserStatsRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                val eventRepo = ListeningEventRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                val activities = ActivityRepository(db = sql)
                val recorder = recorderWith(userStatsRepo, activities, testClock)

                runTest {
                    userStatsRepo.upsert(emptyUserStats("u1", currentStreakDays = 6), clientOpId = null, userId = "u1")

                    var last: ListeningEventSyncPayload? = null
                    for (n in 0..7) {
                        val e = eventAt("e$n", "b1", endedAtMs = day0Ms + n * dayMs, wallSeconds = 30L)
                        eventRepo.upsert(e, clientOpId = null, userId = "u1")
                        last = e
                    }
                    recorder.record(StatsEvent.ListeningSessionClosed(userId = "u1", span = last!!))

                    userStatsRepo.getForUser("u1").shouldNotBeNull().currentStreakDays shouldBe 8

                    val streakMilestones =
                        activities.page(before = null, limit = 100).filter { it.type == ActivityType.STREAK_MILESTONE }
                    streakMilestones shouldHaveSize 1
                    streakMilestones.first().milestoneValue shouldBe 7
                    streakMilestones.first().milestoneUnit shouldBe "days"
                }
            }
        }

        test("streak decrease onto a milestone value emits nothing") {
            withSqlDatabase {
                sql.seedTestUser("u1")
                val testClock = FixedClock(Instant.fromEpochMilliseconds(day0Ms + 6 * dayMs))
                val userStatsRepo = UserStatsRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                val eventRepo = ListeningEventRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                val activities = ActivityRepository(db = sql)
                val recorder = recorderWith(userStatsRepo, activities, testClock)

                runTest {
                    userStatsRepo.upsert(emptyUserStats("u1", currentStreakDays = 14), clientOpId = null, userId = "u1")

                    var last: ListeningEventSyncPayload? = null
                    for (n in 0..6) {
                        val e = eventAt("e$n", "b1", endedAtMs = day0Ms + n * dayMs, wallSeconds = 30L)
                        eventRepo.upsert(e, clientOpId = null, userId = "u1")
                        last = e
                    }
                    recorder.record(StatsEvent.ListeningSessionClosed(userId = "u1", span = last!!))

                    userStatsRepo.getForUser("u1").shouldNotBeNull().currentStreakDays shouldBe 7

                    activities
                        .page(before = null, limit = 100)
                        .filter { it.type == ActivityType.STREAK_MILESTONE } shouldHaveSize 0
                }
            }
        }

        test("exact forward crossing still emits exactly once") {
            withSqlDatabase {
                sql.seedTestUser("u1")
                val testClock = FixedClock(Instant.fromEpochMilliseconds(day0Ms + 6 * dayMs))
                val userStatsRepo = UserStatsRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                val eventRepo = ListeningEventRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                val activities = ActivityRepository(db = sql)
                val recorder = recorderWith(userStatsRepo, activities, testClock)

                runTest {
                    userStatsRepo.upsert(emptyUserStats("u1", currentStreakDays = 6), clientOpId = null, userId = "u1")

                    var last: ListeningEventSyncPayload? = null
                    for (n in 0..6) {
                        val e = eventAt("e$n", "b1", endedAtMs = day0Ms + n * dayMs, wallSeconds = 30L)
                        eventRepo.upsert(e, clientOpId = null, userId = "u1")
                        last = e
                    }
                    recorder.record(StatsEvent.ListeningSessionClosed(userId = "u1", span = last!!))

                    userStatsRepo.getForUser("u1").shouldNotBeNull().currentStreakDays shouldBe 7

                    val streakMilestones =
                        activities.page(before = null, limit = 100).filter { it.type == ActivityType.STREAK_MILESTONE }
                    streakMilestones shouldHaveSize 1
                    streakMilestones.first().milestoneValue shouldBe 7
                }
            }
        }

        test("concurrent double-close emits a crossed milestone exactly once") {
            withSqlDatabase {
                sql.seedTestUser("u1")
                val testClock = FixedClock(Instant.fromEpochMilliseconds(day0Ms))
                val userStatsRepo = UserStatsRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                val eventRepo = ListeningEventRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                val activities = ActivityRepository(db = sql)
                val recorder = recorderWith(userStatsRepo, activities, testClock)

                runTest {
                    // Establish a base below 10h (9h): no milestone crossed yet.
                    val e0 = eventAt("e0", "b1", endedAtMs = day0Ms, wallSeconds = 9L * 3600L)
                    eventRepo.upsert(e0, clientOpId = null, userId = "u1")
                    recorder.record(StatsEvent.ListeningSessionClosed(userId = "u1", span = e0))
                    activities
                        .page(before = null, limit = 100)
                        .filter { it.type == ActivityType.LISTENING_MILESTONE } shouldHaveSize 0

                    // Two more events (30 min each) push the total across the 10h line. Commit both
                    // event rows before racing the cascades, so both derives see the full total.
                    val eA = eventAt("eA", "b1", endedAtMs = day0Ms + 1, wallSeconds = 1_800L)
                    val eB = eventAt("eB", "b1", endedAtMs = day0Ms + 2, wallSeconds = 1_800L)
                    eventRepo.upsert(eA, clientOpId = null, userId = "u1")
                    eventRepo.upsert(eB, clientOpId = null, userId = "u1")

                    coroutineScope {
                        launch { recorder.record(StatsEvent.ListeningSessionClosed(userId = "u1", span = eA)) }
                        launch { recorder.record(StatsEvent.ListeningSessionClosed(userId = "u1", span = eB)) }
                    }

                    activities
                        .page(before = null, limit = 100)
                        .filter { it.type == ActivityType.LISTENING_MILESTONE }
                        .also { it shouldHaveSize 1 }
                        .first()
                        .milestoneValue shouldBe 10
                }
            }
        }

        test("BookCompleted + SessionClosed double-cascade emits the milestone exactly once") {
            withSqlDatabase {
                sql.seedTestUser("u1")
                val testClock = FixedClock(Instant.fromEpochMilliseconds(day0Ms))
                val userStatsRepo = UserStatsRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                val eventRepo = ListeningEventRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                val activities = ActivityRepository(db = sql)
                val recorder = recorderWith(userStatsRepo, activities, testClock)

                runTest {
                    val e0 = eventAt("e0", "b1", endedAtMs = day0Ms, wallSeconds = 9L * 3600L)
                    eventRepo.upsert(e0, clientOpId = null, userId = "u1")
                    recorder.record(StatsEvent.ListeningSessionClosed(userId = "u1", span = e0))

                    val eA = eventAt("eA", "b1", endedAtMs = day0Ms + 1, wallSeconds = 1_800L)
                    val eB = eventAt("eB", "b1", endedAtMs = day0Ms + 2, wallSeconds = 1_800L)
                    eventRepo.upsert(eA, clientOpId = null, userId = "u1")
                    eventRepo.upsert(eB, clientOpId = null, userId = "u1")

                    coroutineScope {
                        launch {
                            recorder.record(
                                StatsEvent.BookCompleted(
                                    userId = "u1",
                                    bookId = "b1",
                                    occurredAt = Instant.fromEpochMilliseconds(day0Ms),
                                ),
                            )
                        }
                        launch { recorder.record(StatsEvent.ListeningSessionClosed(userId = "u1", span = eB)) }
                    }

                    val milestones =
                        activities.page(before = null, limit = 100).filter { it.type == ActivityType.LISTENING_MILESTONE }
                    milestones shouldHaveSize 1
                    milestones.first().milestoneValue shouldBe 10

                    userStatsRepo.getForUser("u1").shouldNotBeNull().totalSecondsAllTime shouldBe
                        9L * 3600L + 1_800L + 1_800L
                }
            }
        }

        test("concurrent same-id replay records one LISTENING_SESSION") {
            withSqlDatabase {
                sql.seedTestUser("u1")
                val testClock = FixedClock(Instant.fromEpochMilliseconds(day0Ms))
                val userStatsRepo = UserStatsRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                val activities = ActivityRepository(db = sql)
                val recorder = recorderWith(userStatsRepo, activities, testClock)
                val eventRepo =
                    ListeningEventRepository(
                        db = sql,
                        bus = ChangeBus(),
                        registry = SyncRegistry(),
                        statsRecorder = recorder,
                    )

                runTest {
                    val payload = eventAt("shared-id", "b1", endedAtMs = day0Ms, wallSeconds = 30L)

                    coroutineScope {
                        launch { eventRepo.upsert(payload, clientOpId = null, userId = "u1") }
                        launch { eventRepo.upsert(payload, clientOpId = null, userId = "u1") }
                    }

                    activities
                        .page(before = null, limit = 100)
                        .filter { it.type == ActivityType.LISTENING_SESSION } shouldHaveSize 1
                }
            }
        }
    })
