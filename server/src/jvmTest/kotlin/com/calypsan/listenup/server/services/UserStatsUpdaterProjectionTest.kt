@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.ListeningEventSyncPayload
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.PublicProfileRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class UserStatsUpdaterProjectionTest :
    FunSpec({
        test("onListeningEvent refreshes the public_profiles projection") {
            withSqlDatabase {
                sql.seedTestUser("u1")

                val ppRepo = PublicProfileRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                val statsRepo =
                    UserStatsRepository(
                        db = sql,
                        bus = ChangeBus(),
                        registry = SyncRegistry(),
                    )
                val maintainer = PublicProfileMaintainer(sql = sql, publicProfileRepo = ppRepo)
                val updater =
                    UserStatsUpdater(
                        sql = sql,
                        userStatsRepo = statsRepo,
                        publicProfileMaintainerProvider = { maintainer },
                    )

                val event =
                    ListeningEventSyncPayload(
                        id = "evt-1",
                        bookId = "b1",
                        startPositionMs = 0L,
                        endPositionMs = 60_000L,
                        startedAt = 1_000L,
                        endedAt = 61_000L,
                        playbackSpeed = 1.0f,
                        tz = "UTC",
                        deviceLabel = null,
                        revision = 0L,
                        updatedAt = 0L,
                        createdAt = 0L,
                        deletedAt = null,
                    )

                runTest {
                    updater.onListeningEvent("u1", event)

                    val proj = ppRepo.pullSince(userId = null, cursor = 0L, limit = 10).items.single()
                    proj.id shouldBe "u1"
                    proj.totalSecondsAllTime shouldBe 60L // (61_000 - 1_000) / 1_000
                }
            }
        }
    })
