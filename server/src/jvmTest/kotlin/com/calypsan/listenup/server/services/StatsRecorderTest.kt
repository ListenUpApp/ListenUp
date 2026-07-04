@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.activity.ActivityType
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.PublicProfileRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.activityRecorder
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

class StatsRecorderTest :
    FunSpec({

        test("BookCompleted updates public_profiles.booksFinishedLast7Days immediately") {
            val nowMs = 1_700_000_000_000L
            val finishedAtMs = nowMs - 2 * 86_400_000L // 2 days ago — inside the 7-day window
            withSqlDatabase {
                sql.seedTestUser("u1")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val userStatsRepo = UserStatsRepository(db = sql, bus = bus, registry = registry)
                val bookReadsRepo = BookReadsRepository(db = sql)
                val publicProfileRepo = PublicProfileRepository(db = sql, bus = bus, registry = registry)
                val publicProfileMaintainer =
                    PublicProfileMaintainer(
                        sql = sql,
                        publicProfileRepo = publicProfileRepo,
                        clock = FixedClock(Instant.fromEpochMilliseconds(nowMs)),
                    )
                val activityRecorder = activityRecorder(bus = bus)
                val statsBackfill = UserStatsBackfillService(sql = sql, userStatsRepo = userStatsRepo)
                val recorder =
                    StatsRecorder(
                        sql = sql,
                        userStatsRepo = userStatsRepo,
                        bookReadsRepository = bookReadsRepo,
                        publicProfileMaintainer = publicProfileMaintainer,
                        activityRecorder = activityRecorder,
                        statsBackfill = statsBackfill,
                    )

                runTest {
                    recorder.record(
                        StatsEvent.BookCompleted(
                            userId = "u1",
                            bookId = "book-1",
                            occurredAt = Instant.fromEpochMilliseconds(finishedAtMs),
                        ),
                    )

                    val profile = publicProfileRepo.pullSince(userId = null, cursor = 0L, limit = 10).items.single()
                    profile.booksFinishedLast7Days shouldBe 1
                }
            }
        }

        test("BookCompleted writes book_reads, bumps booksFinished, and records FINISHED_BOOK — in that order") {
            val nowMs = 1_700_000_000_000L
            withSqlDatabase {
                sql.seedTestUser("u1")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val userStatsRepo = UserStatsRepository(db = sql, bus = bus, registry = registry)
                val bookReadsRepo = BookReadsRepository(db = sql)
                val publicProfileRepo = PublicProfileRepository(db = sql, bus = bus, registry = registry)
                val publicProfileMaintainer =
                    PublicProfileMaintainer(
                        sql = sql,
                        publicProfileRepo = publicProfileRepo,
                        clock = FixedClock(Instant.fromEpochMilliseconds(nowMs)),
                    )
                val activityRepo = ActivityRepository(db = sql)
                val activityRecorder = activityRecorder(bus = bus)
                val statsBackfill = UserStatsBackfillService(sql = sql, userStatsRepo = userStatsRepo)
                val recorder =
                    StatsRecorder(
                        sql = sql,
                        userStatsRepo = userStatsRepo,
                        bookReadsRepository = bookReadsRepo,
                        publicProfileMaintainer = publicProfileMaintainer,
                        activityRecorder = activityRecorder,
                        statsBackfill = statsBackfill,
                    )

                runTest {
                    recorder.record(
                        StatsEvent.BookCompleted(
                            userId = "u1",
                            bookId = "book-1",
                            occurredAt = Instant.fromEpochMilliseconds(nowMs),
                        ),
                    )

                    bookReadsRepo.finishesForUserBook("u1", "book-1") shouldBe listOf(nowMs)
                    userStatsRepo.getForUser("u1")?.booksFinished shouldBe 1
                    val finished =
                        activityRepo.page(before = null, limit = 10).filter { it.type == ActivityType.FINISHED_BOOK }
                    finished shouldHaveSize 1
                    finished.single().bookId shouldBe "book-1"
                    finished.single().occurredAt shouldBe nowMs
                }
            }
        }
    })
