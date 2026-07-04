@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.ListeningEventSyncPayload
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.PublicProfileRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.activityRecorder
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * The rolling 7/30-day window totals must be anchored at the present, not at the
 * event being processed. Processing a late/backfilled event (the normal
 * offline-sync replay path) with a window anchored at `event.endedAt` would
 * overwrite `totalSecondsLast7/30Days` with a total computed for a window in the
 * past — wrong materialized stats. This pins the now-anchored contract.
 */
class StatsRecorderWindowAnchorTest :
    FunSpec({

        val nowMs = 1_779_451_200_000L
        val dayMs = 86_400_000L
        val clock = FixedClock(Instant.fromEpochMilliseconds(nowMs))

        test("a backfilled event 10 days old is excluded from the now-anchored 7-day window") {
            withSqlDatabase {
                sql.seedTestUser("u1")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val statsRepo = UserStatsRepository(db = sql, bus = bus, registry = registry)
                val eventRepo = ListeningEventRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                val recorder =
                    StatsRecorder(
                        sql = sql,
                        userStatsRepo = statsRepo,
                        bookReadsRepository = BookReadsRepository(db = sql),
                        publicProfileMaintainer =
                            PublicProfileMaintainer(
                                sql = sql,
                                publicProfileRepo = PublicProfileRepository(db = sql, bus = bus, registry = registry),
                                clock = clock,
                            ),
                        activityRecorder = activityRecorder(bus = bus),
                        statsBackfill = UserStatsBackfillService(sql = sql, userStatsRepo = statsRepo),
                        clock = clock,
                    )

                runTest {
                    val endedAt = nowMs - 10 * dayMs
                    val event =
                        ListeningEventSyncPayload(
                            id = "old-evt",
                            bookId = "b1",
                            startPositionMs = 0L,
                            endPositionMs = 30_000L,
                            startedAt = endedAt - 30_000L,
                            endedAt = endedAt,
                            playbackSpeed = 1.0f,
                            tz = "UTC",
                            deviceLabel = null,
                            revision = 0L,
                            updatedAt = 0L,
                            createdAt = 0L,
                            deletedAt = null,
                        )
                    eventRepo.upsert(event, clientOpId = null, userId = "u1")
                    recorder.record(StatsEvent.ListeningSessionClosed(userId = "u1", span = event))

                    val stats = statsRepo.getForUser("u1").shouldNotBeNull()
                    // All-time always counts it.
                    stats.totalSecondsAllTime shouldBe 30L
                    // 10 days ago is outside the now-anchored 7-day window …
                    stats.totalSecondsLast7Days shouldBe 0L
                    // … but inside the now-anchored 30-day window.
                    stats.totalSecondsLast30Days shouldBe 30L
                }
            }
        }
    })
