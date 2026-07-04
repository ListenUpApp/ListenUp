@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

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
import kotlinx.coroutines.withContext

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
                        activityRecorder = activityRecorder(bus = bus),
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

        test(
            "StatsCascadeDeferred suppresses public_profiles refresh across several BookCompleted rows; " +
                "BulkRecompute refreshes exactly once",
        ) {
            val nowMs = 1_700_000_000_000L
            withSqlDatabase {
                sql.seedTestUser("u1")
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
                        activityRecorder = activityRecorder(bus = bus),
                        statsBackfill =
                            UserStatsBackfillService(
                                sql = sql,
                                userStatsRepo = userStatsRepo,
                                clock = FixedClock(Instant.fromEpochMilliseconds(nowMs)),
                            ),
                    )

                // Observable equivalent of "PublicProfileMaintainer.refresh() was called N times":
                // every refresh() ends in publicProfileRepo.upsert(), which — per
                // SqlSyncableRepository.upsert's afterCommit hook — publishes exactly one
                // BusEvent<PublicProfileSyncPayload> tagged with domainName = "public_profiles"
                // onto this shared bus. ChangeBus retains a 256-entry replay buffer, so counting
                // that domain's entries in the replay cache is a direct, tooling-free proxy for the
                // refresh call count (PublicProfileMaintainer is a concrete class with no test
                // double available in :server's jvmTest — mokkery's compiler plugin isn't applied
                // to this module — so this counts the class's one durable side effect instead of
                // spying the call itself).
                fun publicProfileWriteCount(): Int = bus.subscribe().replayCache.count { it.repo.domainName == "public_profiles" }

                runTest {
                    withContext(StatsCascadeDeferred) {
                        repeat(3) { i ->
                            recorder.record(
                                StatsEvent.BookCompleted(
                                    userId = "u1",
                                    bookId = "book-$i",
                                    occurredAt = Instant.fromEpochMilliseconds(nowMs),
                                ),
                            )
                        }
                    }

                    // The suppression half: three completions ran under the deferred marker and
                    // none of them refreshed the projection.
                    publicProfileWriteCount() shouldBe 0

                    recorder.record(StatsEvent.BulkRecompute(userId = "u1"))

                    // The terminal half: the bulk import's one closing recompute refreshes exactly
                    // once, not once per suppressed row.
                    publicProfileWriteCount() shouldBe 1
                }
            }
        }
    })
