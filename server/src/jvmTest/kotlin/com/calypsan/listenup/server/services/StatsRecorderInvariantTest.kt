@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.activity.ActivityType
import com.calypsan.listenup.api.sync.ListeningEventSyncPayload
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.PublicProfileRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * The regression net from `docs/superpowers/specs/2026-06-30-statsrecorder-design.md` §5.2: for
 * every [StatsEvent], the all-time counter (`user_stats`), the windowed value
 * (`user_stats`/`public_profiles`), the projection (`public_profiles`), and — for completions — the
 * `activities` row with the correct `occurred_at` all move together. A future write path that bumps
 * one without the others fails here, not in production.
 */
class StatsRecorderInvariantTest :
    FunSpec({

        fun harness(
            sql: com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase,
            driver: app.cash.sqldelight.db.SqlDriver,
            nowMs: Long,
        ): Triple<StatsRecorder, UserStatsRepository, PublicProfileRepository> {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            val userStatsRepo = UserStatsRepository(db = sql, bus = bus, registry = registry)
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
                    activityRecorder =
                        ActivityRecorder(
                            syncRepo = ActivitySyncRepository(db = sql, bus = bus, registry = SyncRegistry(), driver = driver),
                        ),
                    statsBackfill =
                        UserStatsBackfillService(
                            sql = sql,
                            userStatsRepo = userStatsRepo,
                            clock = FixedClock(Instant.fromEpochMilliseconds(nowMs)),
                        ),
                    clock = FixedClock(Instant.fromEpochMilliseconds(nowMs)),
                )
            return Triple(recorder, userStatsRepo, publicProfileRepo)
        }

        test("BookCompleted: counter, window, projection, and activity all move together") {
            val nowMs = 1_700_000_000_000L
            withSqlDatabase {
                sql.seedTestUser("u1")
                val (recorder, userStatsRepo, publicProfileRepo) = harness(sql, driver, nowMs)
                val activities = ActivityRepository(db = sql)

                runTest {
                    recorder.record(
                        StatsEvent.BookCompleted(
                            userId = "u1",
                            bookId = "book-1",
                            occurredAt = Instant.fromEpochMilliseconds(nowMs - 86_400_000L),
                        ),
                    )

                    val stats = userStatsRepo.getForUser("u1").shouldNotBeNull()
                    stats.booksFinished shouldBe 1
                    // Spec §6: a completion moves booksFinished but must never fabricate listening
                    // time or touch the streak — no synthesized listening_events.
                    stats.totalSecondsAllTime shouldBe 0
                    stats.currentStreakDays shouldBe 0
                    val profile = publicProfileRepo.pullSince(userId = null, cursor = 0L, limit = 10).items.single()
                    profile.booksFinished shouldBe 1
                    profile.booksFinishedLast7Days shouldBe 1
                    val finished = activities.page(before = null, limit = 10).filter { it.type == ActivityType.FINISHED_BOOK }
                    finished shouldHaveSize 1
                    finished.single().occurredAt shouldBe nowMs - 86_400_000L
                }
            }
        }

        test("ListeningSessionClosed: counter, window, and projection all move together") {
            val nowMs = 1_700_000_000_000L
            withSqlDatabase {
                sql.seedTestUser("u1")
                val (recorder, userStatsRepo, publicProfileRepo) = harness(sql, driver, nowMs)
                val eventRepo = ListeningEventRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())

                runTest {
                    val event =
                        ListeningEventSyncPayload(
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
                        )
                    eventRepo.upsert(event, clientOpId = null, userId = "u1")
                    recorder.record(StatsEvent.ListeningSessionClosed(userId = "u1", span = event))

                    userStatsRepo.getForUser("u1").shouldNotBeNull().totalSecondsAllTime shouldBe 60L
                    val profile = publicProfileRepo.pullSince(userId = null, cursor = 0L, limit = 10).items.single()
                    profile.totalSecondsAllTime shouldBe 60L
                    profile.totalSecondsLast7Days shouldBe 60L
                }
            }
        }

        test("BookRestarted: no counter or projection movement, exactly one STARTED_BOOK activity") {
            val nowMs = 1_700_000_000_000L
            withSqlDatabase {
                sql.seedTestUser("u1")
                val (recorder, userStatsRepo, publicProfileRepo) = harness(sql, driver, nowMs)
                val activities = ActivityRepository(db = sql)

                runTest {
                    recorder.record(
                        StatsEvent.BookRestarted(
                            userId = "u1",
                            bookId = "book-1",
                            occurredAt = Instant.fromEpochMilliseconds(nowMs),
                            isReread = false,
                        ),
                    )

                    userStatsRepo.getForUser("u1") shouldBe null
                    publicProfileRepo.pullSince(userId = null, cursor = 0L, limit = 10).items.shouldBeEmpty()
                    val started = activities.page(before = null, limit = 10).filter { it.type == ActivityType.STARTED_BOOK }
                    started shouldHaveSize 1
                }
            }
        }

        test("BulkRecompute: counter and projection rebuild from raw source rows in one call") {
            val nowMs = 1_700_000_000_000L
            withSqlDatabase {
                sql.seedTestUser("u1")
                val (recorder, userStatsRepo, publicProfileRepo) = harness(sql, driver, nowMs)
                val eventRepo = ListeningEventRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())

                runTest {
                    eventRepo.upsert(
                        ListeningEventSyncPayload(
                            id = "evt-bulk",
                            bookId = "book-1",
                            startPositionMs = 0L,
                            endPositionMs = 120_000L,
                            startedAt = nowMs - 120_000L,
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

                    userStatsRepo.getForUser("u1").shouldNotBeNull().totalSecondsAllTime shouldBe 120L
                    val profile = publicProfileRepo.pullSince(userId = null, cursor = 0L, limit = 10).items.single()
                    profile.totalSecondsAllTime shouldBe 120L
                }
            }
        }
    })
