@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.ListeningEventSyncPayload
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.activityRecorder
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.noOpPublicProfileMaintainer
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * The pending-op queue's at-least-once delivery means a `listening_events` upsert
 * can be re-fired after the server already committed it (ack lost to a network
 * flap, a client crash/restart in the ack window). The append-only `writePayload`
 * already treats a duplicate id as a no-op for domain fields — but the materialized
 * `StatsRecorder.record(StatsEvent.ListeningSessionClosed(...))` side-effect must be equally
 * idempotent, or a single re-fire permanently inflates the user's all-time listening total.
 *
 * Pins the contract the OpSender/DTO KDocs already promise: re-firing a committed
 * event is safe, the stats are counted exactly once.
 */
class ListeningEventStatsIdempotencyTest :
    FunSpec({

        // 2026-05-22 12:00:00 UTC
        val day0Ms = 1_779_451_200_000L
        val clock = FixedClock(Instant.fromEpochMilliseconds(day0Ms))

        test("re-firing the same listening event does not double-count totalSecondsAllTime") {
            withSqlDatabase {
                sql.seedTestUser("u1")
                val statsRepo = UserStatsRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                val recorder =
                    StatsRecorder(
                        sql = sql,
                        userStatsRepo = statsRepo,
                        bookReadsRepository = BookReadsRepository(db = sql),
                        publicProfileMaintainer = sql.noOpPublicProfileMaintainer(),
                        activityRecorder = activityRecorder(),
                        statsBackfill = UserStatsBackfillService(sql = sql, userStatsRepo = statsRepo),
                        clock = clock,
                    )
                // The repository fires the stats hook internally on a successful upsert —
                // wire the real recorder so the idempotency guard is exercised end-to-end.
                val eventRepo =
                    ListeningEventRepository(
                        db = sql,
                        bus = ChangeBus(),
                        registry = SyncRegistry(),
                        statsRecorder = recorder,
                    )

                runTest {
                    val event =
                        ListeningEventSyncPayload(
                            id = "evt-1",
                            bookId = "book-1",
                            startPositionMs = 0L,
                            endPositionMs = 30_000L,
                            startedAt = day0Ms - 30_000L,
                            endedAt = day0Ms,
                            playbackSpeed = 1.0f,
                            tz = "UTC",
                            deviceLabel = null,
                            revision = 0L,
                            updatedAt = 0L,
                            createdAt = 0L,
                            deletedAt = null,
                        )

                    // First delivery, then an ack-lost re-fire of the SAME event id.
                    eventRepo.upsert(event, clientOpId = null, userId = "u1")
                    eventRepo.upsert(event, clientOpId = "retry", userId = "u1")

                    val stats = statsRepo.getForUser("u1").shouldNotBeNull()
                    // 30 seconds counted once — not 60.
                    stats.totalSecondsAllTime shouldBe 30L
                }
            }
        }
    })
