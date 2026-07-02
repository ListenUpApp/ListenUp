@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.ListeningEventSyncPayload
import com.calypsan.listenup.api.sync.UserStatsSyncPayload
import com.calypsan.listenup.server.scheduler.StatsFreshnessSweepTask
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
 * Spec 005: a lapsed user's current streak must decay to 0 — in `user_stats` AND in the
 * `public_profiles` leaderboard everyone else reads — even though no new event arrives to trigger the
 * write cascade. The lazy pull path heals a user who opens the app; the periodic sweep heals a user who
 * never does.
 */
class StatsFreshnessTest :
    FunSpec({

        val nowMs = 1_779_451_200_000L // 2026-05-22 12:00:00 UTC
        val dayMs = 86_400_000L

        fun eventAt(
            id: String,
            endedAtMs: Long,
        ): ListeningEventSyncPayload =
            ListeningEventSyncPayload(
                id = id,
                bookId = "b1",
                startPositionMs = 0L,
                endPositionMs = 30_000L,
                startedAt = endedAtMs - 30_000L,
                endedAt = endedAtMs,
                playbackSpeed = 1.0f,
                tz = "UTC",
                deviceLabel = null,
                revision = 0L,
                updatedAt = 0L,
                createdAt = 0L,
                deletedAt = null,
            )

        // A 5-day streak that ended 3 days ago: as of now it has lapsed (last listening day is older
        // than yesterday), so a re-derive against "now" must report currentStreakDays = 0.
        suspend fun seedLapsedUser(
            statsRepo: UserStatsRepository,
            eventRepo: ListeningEventRepository,
        ) {
            (3..7).forEach { d -> eventRepo.upsert(eventAt("evt-$d", nowMs - d * dayMs), clientOpId = null, userId = "u1") }
            statsRepo.upsert(
                UserStatsSyncPayload(
                    id = "u1",
                    totalSecondsAllTime = 150L,
                    totalSecondsLast7Days = 150L,
                    totalSecondsLast30Days = 150L,
                    booksStarted = 1,
                    booksFinished = 0,
                    currentStreakDays = 5,
                    longestStreakDays = 5,
                    lastEventDate = "2026-05-19",
                    revision = 0L,
                    updatedAt = 0L,
                    createdAt = 0L,
                    deletedAt = null,
                ),
                clientOpId = null,
                userId = "u1",
            )
        }

        test("lazy pull path decays a lapsed streak in user_stats AND the public_profiles projection") {
            withSqlDatabase {
                sql.seedTestUser("u1")
                val thenClock = FixedClock(Instant.fromEpochMilliseconds(nowMs - 2 * 60 * 60 * 1_000L)) // 2h ago → stale
                val nowClock = FixedClock(Instant.fromEpochMilliseconds(nowMs))
                val bus = ChangeBus()
                val statsRepoThen = UserStatsRepository(db = sql, bus = bus, registry = SyncRegistry(), clock = thenClock)
                val eventRepo = ListeningEventRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                val publicProfileRepo = PublicProfileRepository(db = sql, bus = bus, registry = SyncRegistry())
                val maintainer = PublicProfileMaintainer(sql = sql, publicProfileRepo = publicProfileRepo, clock = nowClock)

                runTest {
                    seedLapsedUser(statsRepoThen, eventRepo)
                    // Seed the projection at streak = 5 (what everyone else currently sees on the leaderboard).
                    maintainer.refresh("u1")
                    publicProfileRepo
                        .pullSince(userId = null, cursor = 0L, limit = 10)
                        .items
                        .single { it.id == "u1" }
                        .currentStreakDays shouldBe 5

                    val updater =
                        UserStatsUpdater(sql = sql, userStatsRepo = statsRepoThen, publicProfileMaintainer = maintainer)
                    val statsRepoDecay =
                        UserStatsRepository(
                            db = sql,
                            bus = bus,
                            registry = SyncRegistry(),
                            clock = nowClock,
                            userStatsUpdaterProvider = { updater },
                        )

                    statsRepoDecay.pullSince(userId = "u1", cursor = 0L, limit = 50)

                    statsRepoThen.getForUser("u1").shouldNotBeNull().currentStreakDays shouldBe 0
                    publicProfileRepo
                        .pullSince(userId = null, cursor = 0L, limit = 10)
                        .items
                        .single { it.id == "u1" }
                        .currentStreakDays shouldBe 0
                }
            }
        }

        test("the freshness sweep decays a lapsed streak for an idle user who never pulls") {
            withSqlDatabase {
                sql.seedTestUser("u1")
                val nowClock = FixedClock(Instant.fromEpochMilliseconds(nowMs))
                val bus = ChangeBus()
                val statsRepo = UserStatsRepository(db = sql, bus = bus, registry = SyncRegistry(), clock = nowClock)
                val eventRepo = ListeningEventRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                val publicProfileRepo = PublicProfileRepository(db = sql, bus = bus, registry = SyncRegistry())
                val maintainer = PublicProfileMaintainer(sql = sql, publicProfileRepo = publicProfileRepo, clock = nowClock)

                runTest {
                    seedLapsedUser(statsRepo, eventRepo)
                    maintainer.refresh("u1")

                    val updater =
                        UserStatsUpdater(sql = sql, userStatsRepo = statsRepo, publicProfileMaintainer = maintainer)
                    val healed =
                        StatsFreshnessSweepTask(sql = sql, updater = updater, clock = nowClock).runOnce()

                    healed shouldBe 1
                    statsRepo.getForUser("u1").shouldNotBeNull().currentStreakDays shouldBe 0
                    publicProfileRepo
                        .pullSince(userId = null, cursor = 0L, limit = 10)
                        .items
                        .single { it.id == "u1" }
                        .currentStreakDays shouldBe 0
                }
            }
        }
    })
