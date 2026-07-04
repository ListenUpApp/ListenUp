@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.ListeningEventSyncPayload
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.PublicProfileRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.activityRecorder
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * Spec 003: `user_stats` is a pure re-derivation from the `listening_events` primitive on every
 * trigger, not a path-dependent increment. That makes three properties hold by construction, each
 * pinned below:
 *
 *  1. **Crash-healing** — if the stats write was skipped for an event (process died between the event
 *     commit and the stats cascade), the next event's re-derive recovers the missed seconds; nothing
 *     is undercounted forever.
 *  2. **Order-independence** — events arriving out of order (multi-device / offline-outbox replay)
 *     converge to the same `user_stats` as sorted arrival.
 *  3. **Live == backfill** — the per-event live path and `UserStatsBackfillService.backfillFor`
 *     produce identical rows from the same events, because both call the one derivation.
 */
class StatsRecorderRederiveTest :
    FunSpec({

        val day0Ms = 1_779_451_200_000L // 2026-05-22 12:00:00 UTC
        val dayMs = 86_400_000L

        fun eventAt(
            id: String,
            bookId: String,
            endedAtMs: Long,
            wallSeconds: Long = 30L,
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
                statsBackfill = UserStatsBackfillService(sql = sql, userStatsRepo = userStatsRepo, clock = testClock),
                clock = testClock,
            )
        }

        test("a missed stats write self-heals: the next event's re-derive recovers the lost seconds") {
            withSqlDatabase {
                sql.seedTestUser("u1")
                val clock = FixedClock(Instant.fromEpochMilliseconds(day0Ms + dayMs))
                val userStatsRepo = UserStatsRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                // eventRepo WITHOUT a recorder: the event row commits, but the stats cascade never runs
                // (simulating a crash between the event commit and the stats write).
                val eventRepo = ListeningEventRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                val recorder = recorderWith(userStatsRepo, clock)

                runTest {
                    val e1 = eventAt("evt-1", "book-1", endedAtMs = day0Ms, wallSeconds = 30L)
                    eventRepo.upsert(e1, clientOpId = null, userId = "u1")
                    // NB: no recorder.record(e1) — its stats write was "lost".
                    userStatsRepo.getForUser("u1") shouldBe null

                    val e2 = eventAt("evt-2", "book-1", endedAtMs = day0Ms + dayMs, wallSeconds = 30L)
                    eventRepo.upsert(e2, clientOpId = null, userId = "u1")
                    recorder.record(StatsEvent.ListeningSessionClosed(userId = "u1", span = e2))

                    val stats = userStatsRepo.getForUser("u1").shouldNotBeNull()
                    // Both events counted — the missed e1 (30s) is healed by e2's re-derive, not lost.
                    stats.totalSecondsAllTime shouldBe 60L
                }
            }
        }

        test("out-of-order arrival converges to the same user_stats as sorted arrival") {
            withSqlDatabase {
                sql.seedTestUser("scr")
                val clock = FixedClock(Instant.fromEpochMilliseconds(day0Ms + 2 * dayMs))
                val repo = UserStatsRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                val eventRepo = ListeningEventRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                val recorder = recorderWith(repo, clock)

                // Scrambled arrival of three consecutive days: day+2, then day0, then day+1.
                val arrival =
                    listOf(
                        "d2" to day0Ms + 2 * dayMs,
                        "d0" to day0Ms,
                        "d1" to day0Ms + dayMs,
                    )
                runTest {
                    for ((id, ms) in arrival) {
                        val e = eventAt("scr-$id", "book-1", endedAtMs = ms)
                        eventRepo.upsert(e, clientOpId = null, userId = "scr")
                        recorder.record(StatsEvent.ListeningSessionClosed(userId = "scr", span = e))
                    }

                    // The scrambled run must equal a clean full recompute over the same 3 consecutive
                    // days: the old incremental path would have frozen the streak at 1 (each later-dated
                    // arrival treated as "late"); the re-derive counts all three days.
                    val scrambled = repo.getForUser("scr").shouldNotBeNull()
                    scrambled.totalSecondsAllTime shouldBe 90L
                    scrambled.currentStreakDays shouldBe 3
                    scrambled.longestStreakDays shouldBe 3
                    scrambled.lastEventDate shouldBe "2026-05-24"
                }
            }
        }

        test("the live per-event path and backfillFor produce identical user_stats") {
            withSqlDatabase {
                sql.seedTestUser("u1")
                val clock = FixedClock(Instant.fromEpochMilliseconds(day0Ms + 2 * dayMs))
                val userStatsRepo = UserStatsRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                val eventRepo = ListeningEventRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                val recorder = recorderWith(userStatsRepo, clock)
                val backfill = UserStatsBackfillService(sql = sql, userStatsRepo = userStatsRepo, clock = clock)

                runTest {
                    val events =
                        listOf(
                            eventAt("e1", "book-1", endedAtMs = day0Ms, wallSeconds = 45L),
                            eventAt("e2", "book-2", endedAtMs = day0Ms + dayMs, wallSeconds = 90L),
                            eventAt("e3", "book-1", endedAtMs = day0Ms + 2 * dayMs, wallSeconds = 30L),
                        )
                    for (e in events) {
                        eventRepo.upsert(e, clientOpId = null, userId = "u1")
                        recorder.record(StatsEvent.ListeningSessionClosed(userId = "u1", span = e))
                    }
                    val live = userStatsRepo.getForUser("u1").shouldNotBeNull()

                    // Now force a full rebuild and compare the derived fields.
                    backfill.backfillFor("u1")
                    val rebuilt = userStatsRepo.getForUser("u1").shouldNotBeNull()

                    rebuilt.totalSecondsAllTime shouldBe live.totalSecondsAllTime
                    rebuilt.totalSecondsLast7Days shouldBe live.totalSecondsLast7Days
                    rebuilt.totalSecondsLast30Days shouldBe live.totalSecondsLast30Days
                    rebuilt.booksStarted shouldBe live.booksStarted
                    rebuilt.currentStreakDays shouldBe live.currentStreakDays
                    rebuilt.longestStreakDays shouldBe live.longestStreakDays
                    rebuilt.lastEventDate shouldBe live.lastEventDate
                }
            }
        }
    })
