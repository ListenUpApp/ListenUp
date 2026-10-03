@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.server.scheduler.StatsFreshnessSweepTask
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.PublicProfileRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.MutableClock
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * The calendar week only moves at the listener's local midnight, so the freshness sweep has to run
 * often enough to catch every user's midnight (wherever they live) and roll their week — in `user_stats`
 * AND in the `public_profiles` leaderboard — without waiting for them to listen or sync again.
 */
class StatsMidnightRolloverTest :
    FunSpec({

        fun at(iso: String): Instant = Instant.parse(iso)

        test("the sweep ticks at least hourly") {
            withSqlDatabase {
                val updater = UserStatsUpdater(sql = sql, userStatsRepo = UserStatsRepository(sql, ChangeBus(), SyncRegistry()))
                val task = StatsFreshnessSweepTask(sql = sql, updater = updater)
                (task.interval <= 1.hours) shouldBe true
            }
        }

        test("an idle listener's week rolls over at their local midnight, and re-running is a no-op") {
            withSqlDatabase {
                sql.seedTestUser("simon", timezone = "America/Edmonton")
                sql.seedTestUser("reader", timezone = "America/Edmonton")
                // 2026-10-02 10:20 MDT.
                val clock = MutableClock(at("2026-10-02T16:20:00Z"))
                val bus = ChangeBus()
                val statsRepo = UserStatsRepository(db = sql, bus = bus, registry = SyncRegistry(), clock = clock)
                val profileRepo = PublicProfileRepository(db = sql, bus = bus, registry = SyncRegistry(), clock = clock)
                val maintainer = PublicProfileMaintainer(sql = sql, publicProfileRepo = profileRepo, clock = clock)
                val updater = UserStatsUpdater(sql = sql, userStatsRepo = statsRepo, publicProfileMaintainer = maintainer)
                val sweep = StatsFreshnessSweepTask(sql = sql, updater = updater, clock = clock)

                // simon: 40 min ending Sep 26 00:20 MDT (the oldest day of this week) + 1h today.
                sql.insertSpan(
                    "edge",
                    userId = "simon",
                    startedAt = at("2026-09-26T05:40:00Z").toEpochMilliseconds(),
                    endedAt = at("2026-09-26T06:20:00Z").toEpochMilliseconds(),
                )
                sql.insertSpan(
                    "today",
                    userId = "simon",
                    startedAt = at("2026-10-02T15:00:00Z").toEpochMilliseconds(),
                    endedAt = at("2026-10-02T16:00:00Z").toEpochMilliseconds(),
                )
                // reader: listened only today, but finished a book on Sep 26 00:30 MDT. Their user_stats
                // won't move at midnight; only the projection's windowed book count does.
                sql.insertSpan(
                    "reader-today",
                    userId = "reader",
                    startedAt = at("2026-10-02T15:00:00Z").toEpochMilliseconds(),
                    endedAt = at("2026-10-02T15:30:00Z").toEpochMilliseconds(),
                )
                sql.insertRead(
                    "reader-finish",
                    userId = "reader",
                    bookId = "b9",
                    finishedAt = at("2026-09-26T06:30:00Z").toEpochMilliseconds(),
                )

                runTest {
                    for (id in listOf("simon", "reader")) {
                        statsRepo.upsert(deriveUserStats(sql, id, clock.now().toEpochMilliseconds()), clientOpId = null, userId = id)
                        maintainer.refresh(id)
                    }

                    suspend fun projected(id: String) =
                        profileRepo
                            .pullSince(userId = null, cursor = 0L, limit = 10)
                            .items
                            .single { it.id == id }

                    projected("simon").totalSecondsLast7Days shouldBe 100 * 60L
                    projected("reader").booksFinishedLast7Days shouldBe 1

                    // Fresh rows, first tick: nothing to heal.
                    sweep.runOnce() shouldBe 0

                    // 23:30 MDT the same day: still the same week.
                    clock.instant = at("2026-10-03T05:30:00Z")
                    sweep.runOnce() shouldBe 0
                    statsRepo.getForUser("simon").shouldNotBeNull().totalSecondsLast7Days shouldBe 100 * 60L

                    // 00:15 MDT Oct 3: Sep 26 has left the week.
                    clock.instant = at("2026-10-03T06:15:00Z")
                    sweep.runOnce() shouldBe 2
                    statsRepo.getForUser("simon").shouldNotBeNull().totalSecondsLast7Days shouldBe 60 * 60L
                    projected("simon").totalSecondsLast7Days shouldBe 60 * 60L
                    projected("reader").booksFinishedLast7Days shouldBe 0

                    // Idempotent: the same tick again changes nothing.
                    sweep.runOnce() shouldBe 0
                }
            }
        }
    })
