@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

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

class StatsRecorderBulkRecomputeTest :
    FunSpec({

        test("BulkRecompute rebuilds user_stats from raw listening_events and refreshes the projection") {
            val nowMs = 1_700_000_000_000L
            withSqlDatabase {
                sql.seedTestUser("u1")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val userStatsRepo = UserStatsRepository(db = sql, bus = bus, registry = registry)
                val eventRepo = ListeningEventRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                val publicProfileRepo = PublicProfileRepository(db = sql, bus = bus, registry = registry)
                val recorder =
                    StatsRecorder(
                        sql = sql,
                        userStatsRepo = userStatsRepo,
                        bookReadsRepository = BookReadsRepository(db = sql),
                        publicProfileMaintainer =
                            PublicProfileMaintainer(
                                sql = sql,
                                publicProfileRepo = publicProfileRepo,
                                clock = FixedClock(Instant.fromEpochMilliseconds(nowMs)),
                            ),
                        activityRecorder = ActivityRecorder(repo = ActivityRepository(db = sql), bus = bus),
                        statsBackfill =
                            UserStatsBackfillService(
                                sql = sql,
                                userStatsRepo = userStatsRepo,
                                clock = FixedClock(Instant.fromEpochMilliseconds(nowMs)),
                            ),
                    )

                runTest {
                    // Raw source row already exists (bypassing the recorder, simulating an import).
                    eventRepo.upsert(
                        com.calypsan.listenup.api.sync.ListeningEventSyncPayload(
                            id = "evt-1",
                            bookId = "book-1",
                            startPositionMs = 0L,
                            endPositionMs = 60_000L,
                            startedAt = nowMs - 60_000L,
                            endedAt = nowMs,
                            playbackSpeed = 1.0f,
                            tz = "UTC",
                            deviceLabel = null,
                            revision = 0L,
                            updatedAt = 0L,
                            createdAt = 0L,
                            deletedAt = null,
                        ),
                        clientOpId = null,
                        userId = "u1",
                    )

                    recorder.record(StatsEvent.BulkRecompute(userId = "u1"))

                    val stats = userStatsRepo.getForUser("u1").shouldNotBeNull()
                    stats.totalSecondsAllTime shouldBe 60L
                    val profile = publicProfileRepo.pullSince(userId = null, cursor = 0L, limit = 10).items.single()
                    profile.totalSecondsAllTime shouldBe 60L
                }
            }
        }
    })
