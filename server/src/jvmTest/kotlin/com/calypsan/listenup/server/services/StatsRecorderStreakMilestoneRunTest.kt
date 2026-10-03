@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.activity.ActivityType
import com.calypsan.listenup.api.sync.ListeningEventSyncPayload
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.PublicProfileRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.MutableClock
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.activityRecorder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant

/**
 * A streak milestone is announced at most once per streak run — keyed by the day the run began plus
 * the milestone value — and dated at the listening that completed the threshold day, never at the
 * moment the server happened to hear about it.
 *
 * Every case runs in America/Edmonton (UTC-6 in this window), so a listen late in a local evening ends
 * on the next UTC day: the run is resolved in the listener's home timezone, never in UTC.
 */
class StatsRecorderStreakMilestoneRunTest :
    FunSpec({

        val edmonton = TimeZone.of("America/Edmonton")
        val userId = "listener"

        fun at(
            date: LocalDate,
            hour: Int,
            minute: Int = 0,
        ): Instant = LocalDateTime(date.year, date.month, date.day, hour, minute).toInstant(edmonton)

        fun span(
            id: String,
            endedAt: Instant,
            wallSeconds: Long = 1_200L,
        ): ListeningEventSyncPayload =
            ListeningEventSyncPayload(
                id = id,
                bookId = "book-1",
                startPositionMs = 0L,
                endPositionMs = wallSeconds * 1_000L,
                startedAt = endedAt.toEpochMilliseconds() - wallSeconds * 1_000L,
                endedAt = endedAt.toEpochMilliseconds(),
                playbackSpeed = 1.0f,
                tz = "America/Edmonton",
                deviceLabel = null,
                revision = 0L,
                updatedAt = 0L,
                createdAt = 0L,
                deletedAt = null,
            )

        class Rig(
            val clock: MutableClock,
            val userStatsRepo: UserStatsRepository,
            val eventRepo: ListeningEventRepository,
            val activities: ActivityRepository,
            val recorder: StatsRecorder,
            val activityRecorder: ActivityRecorder,
            val updater: UserStatsUpdater,
        ) {
            /** Commits [event] and runs its cascade as the server would on arrival at [clock]'s now. */
            suspend fun arrive(event: ListeningEventSyncPayload) {
                eventRepo.upsert(event, clientOpId = null, userId = userId)
                recorder.record(StatsEvent.ListeningSessionClosed(userId = userId, span = event))
            }

            suspend fun streakMilestones() =
                activities
                    .page(before = null, limit = 500)
                    .filter { it.type == ActivityType.STREAK_MILESTONE }
                    .sortedBy { it.occurredAt }
        }

        fun SqlTestDatabases.rig(startsAt: Instant): Rig {
            sql.seedTestUser(userId, timezone = "America/Edmonton")
            val clock = MutableClock(startsAt)
            val bus = ChangeBus()
            val registry = SyncRegistry()
            val userStatsRepo = UserStatsRepository(db = sql, bus = bus, registry = registry)
            val activityRecorder = activityRecorder(bus = bus)
            return Rig(
                clock = clock,
                userStatsRepo = userStatsRepo,
                eventRepo = ListeningEventRepository(db = sql, bus = bus, registry = registry),
                activities = ActivityRepository(db = sql),
                recorder =
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
                        activityRecorder = activityRecorder,
                        statsBackfill = UserStatsBackfillService(sql = sql, userStatsRepo = userStatsRepo),
                        clock = clock,
                    ),
                activityRecorder = activityRecorder,
                updater = UserStatsUpdater(sql = sql, userStatsRepo = userStatsRepo),
            )
        }

        test("a streak that only looked broken because listening synced late announces nothing again") {
            withSqlDatabase {
                val runStart = LocalDate(2026, 8, 21)
                val rig = rig(startsAt = at(runStart, 20))
                runTest {
                    // 42 live days, Aug 21 → Oct 1, each synced the evening it happened. The run crosses
                    // 7, 14 and 30 along the way, exactly once each.
                    for (n in 0 until 42) {
                        val day = runStart.plus(DatePeriod(days = n))
                        rig.clock.instant = at(day, 21)
                        rig.arrive(span("live-$n", endedAt = at(day, 20)))
                    }
                    rig.streakMilestones().map { it.milestoneValue } shouldBe listOf(7, 14, 30)

                    // Oct 3, 07:00 local: the decay sweep sees no listening since Oct 1 and calls the
                    // streak broken — Oct 2's listening is still sitting on the phone.
                    val syncedAt = at(LocalDate(2026, 10, 3), 7)
                    rig.clock.instant = syncedAt
                    rig.updater.healStaleStats(userId, asOfMs = syncedAt.toEpochMilliseconds()) shouldBe true
                    rig.userStatsRepo
                        .getForUser(userId)
                        .shouldNotBeNull()
                        .currentStreakDays shouldBe 0

                    // The phone syncs Oct 2's three evening sessions. The streak is restored to 43, but it
                    // is the same streak: 7, 14 and 30 were already earned in it.
                    val oct2 = LocalDate(2026, 10, 2)
                    rig.arrive(span("late-1", endedAt = at(oct2, 22, 17)))
                    rig.arrive(span("late-2", endedAt = at(oct2, 22, 35)))
                    rig.arrive(span("late-3", endedAt = at(oct2, 22, 50)))

                    rig.userStatsRepo
                        .getForUser(userId)
                        .shouldNotBeNull()
                        .currentStreakDays shouldBe 43
                    rig.streakMilestones().map { it.milestoneValue } shouldBe listOf(7, 14, 30)
                }
            }
        }

        test("a milestone recorded before this fix, dated when it was announced, still counts for its run") {
            withSqlDatabase {
                val runStart = LocalDate(2026, 8, 21)
                val rig = rig(startsAt = at(runStart, 20))
                runTest {
                    for (n in 0 until 42) {
                        rig.eventRepo.upsert(
                            span("history-$n", endedAt = at(runStart.plus(DatePeriod(days = n)), 20)),
                            clientOpId = null,
                            userId = userId,
                        )
                    }
                    // The rows as production holds them: dated at announcement time, mid-run.
                    listOf(7 to LocalDate(2026, 8, 28), 14 to LocalDate(2026, 9, 4), 30 to LocalDate(2026, 9, 20))
                        .forEach { (value, day) ->
                            rig.activityRecorder.record(
                                userId,
                                ActivityType.STREAK_MILESTONE,
                                milestoneValue = value,
                                milestoneUnit = "days",
                                occurredAt = at(day, 7, 45).toEpochMilliseconds(),
                            )
                        }

                    // No user_stats row: the cascade's base is a zero streak, as after a decay.
                    val syncedAt = at(LocalDate(2026, 10, 3), 7)
                    rig.clock.instant = syncedAt
                    rig.arrive(span("late", endedAt = at(LocalDate(2026, 10, 2), 22, 17)))

                    rig.userStatsRepo
                        .getForUser(userId)
                        .shouldNotBeNull()
                        .currentStreakDays shouldBe 43
                    rig.streakMilestones() shouldHaveSize 3
                }
            }
        }

        test("a genuinely new streak after a real gap announces its milestones again") {
            withSqlDatabase {
                val firstRun = LocalDate(2026, 8, 1)
                val rig = rig(startsAt = at(firstRun, 20))
                runTest {
                    // Aug 1–7: the first run reaches 7.
                    for (n in 0 until 7) {
                        val day = firstRun.plus(DatePeriod(days = n))
                        rig.clock.instant = at(day, 21)
                        rig.arrive(span("first-$n", endedAt = at(day, 20)))
                    }
                    // Aug 8: nothing. Aug 9–15: a second run, which reaches 7 on its own.
                    val secondRun = LocalDate(2026, 8, 9)
                    for (n in 0 until 7) {
                        val day = secondRun.plus(DatePeriod(days = n))
                        rig.clock.instant = at(day, 21)
                        rig.arrive(span("second-$n", endedAt = at(day, 20)))
                    }

                    val milestones = rig.streakMilestones()
                    milestones.map { it.milestoneValue } shouldBe listOf(7, 7)
                    milestones.map { it.occurredAt } shouldBe
                        listOf(
                            at(LocalDate(2026, 8, 7), 20).toEpochMilliseconds(),
                            at(LocalDate(2026, 8, 15), 20).toEpochMilliseconds(),
                        )
                }
            }
        }

        test("crossing 7 announces once, dated at the listening that completed the seventh day") {
            withSqlDatabase {
                val runStart = LocalDate(2026, 9, 1)
                val rig = rig(startsAt = at(runStart, 20))
                runTest {
                    for (n in 0 until 6) {
                        val day = runStart.plus(DatePeriod(days = n))
                        rig.clock.instant = at(day, 21)
                        rig.arrive(span("day-$n", endedAt = at(day, 20)))
                    }
                    // Day seven: a morning session completes the day, an evening one adds to it, and
                    // both reach the server together the next morning.
                    val seventh = runStart.plus(DatePeriod(days = 6))
                    rig.clock.instant = at(seventh.plus(DatePeriod(days = 1)), 8)
                    rig.arrive(span("seventh-morning", endedAt = at(seventh, 7, 30)))
                    rig.arrive(span("seventh-evening", endedAt = at(seventh, 21, 10)))

                    val milestones = rig.streakMilestones()
                    milestones shouldHaveSize 1
                    milestones.single().milestoneValue shouldBe 7
                    milestones.single().occurredAt shouldBe at(seventh, 7, 30).toEpochMilliseconds()
                }
            }
        }

        test("a late listen just before local midnight completes the run in the home timezone") {
            withSqlDatabase {
                val runStart = LocalDate(2026, 9, 1)
                val rig = rig(startsAt = at(runStart, 20))
                runTest {
                    // Sept 1–5 live.
                    for (n in 0 until 5) {
                        val day = runStart.plus(DatePeriod(days = n))
                        rig.clock.instant = at(day, 21)
                        rig.arrive(span("day-$n", endedAt = at(day, 20)))
                    }
                    // Sept 7 syncs live; Sept 6's listening hasn't arrived, so the run looks broken.
                    val sixth = LocalDate(2026, 9, 6)
                    val seventh = LocalDate(2026, 9, 7)
                    rig.clock.instant = at(seventh, 19)
                    rig.arrive(span("seventh", endedAt = at(seventh, 18, 30)))
                    rig.userStatsRepo
                        .getForUser(userId)
                        .shouldNotBeNull()
                        .currentStreakDays shouldBe 1

                    // Sept 6's session ended at 23:40 local — 05:40 UTC on Sept 7 — and arrives the next
                    // morning, after Sept 7's. In Edmonton it is Sept 6, and it closes the gap.
                    rig.clock.instant = at(LocalDate(2026, 9, 8), 7)
                    rig.arrive(span("sixth-late", endedAt = at(sixth, 23, 40)))

                    rig.userStatsRepo
                        .getForUser(userId)
                        .shouldNotBeNull()
                        .currentStreakDays shouldBe 7
                    val milestones = rig.streakMilestones()
                    milestones shouldHaveSize 1
                    milestones.single().milestoneValue shouldBe 7
                    milestones.single().occurredAt shouldBe at(seventh, 18, 30).toEpochMilliseconds()
                }
            }
        }
    })
