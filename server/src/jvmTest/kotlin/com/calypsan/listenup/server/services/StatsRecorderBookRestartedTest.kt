@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.activity.ActivityType
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.PublicProfileRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.activityRecorder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

class StatsRecorderBookRestartedTest :
    FunSpec({

        test("BookRestarted records STARTED_BOOK with the given isReread flag and touches no stats") {
            withSqlDatabase {
                sql.seedTestUser("u1")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val userStatsRepo = UserStatsRepository(db = sql, bus = bus, registry = registry)
                val activities = ActivityRepository(db = sql)
                val recorder =
                    StatsRecorder(
                        sql = sql,
                        userStatsRepo = userStatsRepo,
                        bookReadsRepository = BookReadsRepository(db = sql),
                        publicProfileMaintainer =
                            PublicProfileMaintainer(
                                sql = sql,
                                publicProfileRepo = PublicProfileRepository(db = sql, bus = bus, registry = registry),
                            ),
                        activityRecorder = activityRecorder(bus = bus),
                        statsBackfill = UserStatsBackfillService(sql = sql, userStatsRepo = userStatsRepo),
                    )

                runTest {
                    recorder.record(
                        StatsEvent.BookRestarted(
                            userId = "u1",
                            bookId = "book-1",
                            occurredAt = Instant.fromEpochMilliseconds(1_700_000_000_000L),
                            isReread = true,
                        ),
                    )

                    val started = activities.page(before = null, limit = 10).filter { it.type == ActivityType.STARTED_BOOK }
                    started shouldHaveSize 1
                    started.single().isReread shouldBe true
                    userStatsRepo.getForUser("u1") shouldBe null
                }
            }
        }
    })
