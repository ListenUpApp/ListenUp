@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.ListeningEventSyncPayload
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

class StatsRecorderListeningSessionClosedTest :
    FunSpec({

        val day0Ms = 1_779_451_200_000L // 2026-05-22 12:00:00 UTC
        val clock = FixedClock(Instant.fromEpochMilliseconds(day0Ms))

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
                        activityRecorder = ActivityRecorder(repo = ActivityRepository(db = sql), bus = bus),
                        statsBackfill = UserStatsBackfillService(sql = sql, userStatsRepo = userStatsRepo),
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
    })
