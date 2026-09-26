@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.activity.ActivityType
import com.calypsan.listenup.api.dto.activity.RealListen
import com.calypsan.listenup.api.sync.ListeningEventSyncPayload
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.PublicProfileRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.activityRecorder
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * "Started reading" is news only once the listen-through holds a real listen
 * ([RealListen.THRESHOLD_MS] of wall-clock listening). A tap on a book, or opening the wrong one,
 * announces nothing — and when the line is crossed, the announcement is dated when the
 * listen-through began, not when the minute ran out.
 */
class StatsRecorderRealStartTest :
    FunSpec({

        val t0 = 1_779_451_200_000L // 2026-05-22 12:00:00 UTC
        val clock = FixedClock(Instant.fromEpochMilliseconds(t0))

        test("a restart followed by less than a real listen announces nothing") {
            withSqlDatabase {
                val f = fixture(this, clock)
                runTest {
                    f.restart(atMs = t0, isReread = false)
                    f.listen("e1", endedAtMs = t0 + 20_000L, wallMs = 20_000L)

                    f.startedActivities().shouldBeEmpty()
                }
            }
        }

        test("crossing a real listen announces the start once, dated at the listen-through's start") {
            withSqlDatabase {
                val f = fixture(this, clock)
                runTest {
                    f.restart(atMs = t0, isReread = false)
                    f.listen("e1", endedAtMs = t0 + 40_000L, wallMs = 40_000L)
                    f.listen("e2", endedAtMs = t0 + 90_000L, wallMs = 30_000L) // 70s total: crosses
                    f.listen("e3", endedAtMs = t0 + 200_000L, wallMs = 100_000L) // already announced

                    val started = f.startedActivities()
                    started shouldHaveSize 1
                    started.single().occurredAt shouldBe t0
                    started.single().isReread shouldBe false
                }
            }
        }

        test("a re-read is its own listen-through and announces as a re-read") {
            withSqlDatabase {
                val f = fixture(this, clock)
                runTest {
                    f.restart(atMs = t0, isReread = false)
                    f.listen("e1", endedAtMs = t0 + RealListen.THRESHOLD_MS, wallMs = RealListen.THRESHOLD_MS)
                    val rereadAt = t0 + 30L * 86_400_000L
                    f.restart(atMs = rereadAt, isReread = true)
                    f.listen("e2", endedAtMs = rereadAt + 10_000L, wallMs = 10_000L)

                    // Only the first listen-through's announcement so far: the re-read has 10s.
                    f.startedActivities().map { it.isReread } shouldBe listOf(false)

                    f.listen("e3", endedAtMs = rereadAt + 80_000L, wallMs = 70_000L)

                    val started = f.startedActivities().sortedBy { it.occurredAt }
                    started.map { it.isReread } shouldBe listOf(false, true)
                    started.last().occurredAt shouldBe rereadAt
                }
            }
        }

        test("listening from before the restart does not count toward the new listen-through") {
            withSqlDatabase {
                val f = fixture(this, clock)
                runTest {
                    f.listen("old", endedAtMs = t0 - 86_400_000L, wallMs = 3_600_000L)
                    f.restart(atMs = t0, isReread = true)

                    f.startedActivities().shouldBeEmpty()
                }
            }
        }

        test("an import that writes the sessions before the restart still announces once") {
            withSqlDatabase {
                val f = fixture(this, clock)
                runTest {
                    // Events land first (without firing), then the restart is recorded: the check at
                    // restart time sees the listening that is already there.
                    f.persistOnly("e1", endedAtMs = t0 + 90_000L, wallMs = 90_000L)
                    f.restart(atMs = t0, isReread = false)

                    f.startedActivities() shouldHaveSize 1
                }
            }
        }

        test("a book already in progress when this shipped (no listen-through row) never re-announces") {
            withSqlDatabase {
                val f = fixture(this, clock)
                runTest {
                    f.listen("e1", endedAtMs = t0 + 600_000L, wallMs = 600_000L)

                    f.startedActivities().shouldBeEmpty()
                }
            }
        }
    })

private class RealStartFixture(
    val sql: ListenUpDatabase,
    val recorder: StatsRecorder,
    val events: ListeningEventRepository,
    val activities: ActivityRepository,
) {
    suspend fun restart(
        atMs: Long,
        isReread: Boolean,
    ) = recorder.record(
        StatsEvent.BookRestarted(
            userId = "u1",
            bookId = "book-1",
            occurredAt = Instant.fromEpochMilliseconds(atMs),
            isReread = isReread,
        ),
    )

    /** Persists a span and fires its session-closed event, as the live path does. */
    suspend fun listen(
        id: String,
        endedAtMs: Long,
        wallMs: Long,
    ) {
        val span = persistOnly(id, endedAtMs, wallMs)
        recorder.record(StatsEvent.ListeningSessionClosed(userId = "u1", span = span))
    }

    /** Persists a span without firing its event. */
    suspend fun persistOnly(
        id: String,
        endedAtMs: Long,
        wallMs: Long,
    ): ListeningEventSyncPayload {
        val span =
            ListeningEventSyncPayload(
                id = id,
                bookId = "book-1",
                startPositionMs = 0L,
                endPositionMs = wallMs,
                startedAt = endedAtMs - wallMs,
                endedAt = endedAtMs,
                playbackSpeed = 1.0f,
                tz = "UTC",
                deviceLabel = null,
                revision = 0L,
                updatedAt = 0L,
                createdAt = 0L,
                deletedAt = null,
            )
        events.upsert(span, clientOpId = null, userId = "u1")
        return span
    }

    suspend fun startedActivities() = activities.page(before = null, limit = 50).filter { it.type == ActivityType.STARTED_BOOK }
}

private fun fixture(
    dbs: SqlTestDatabases,
    clock: FixedClock,
): RealStartFixture {
    dbs.sql.seedTestUser("u1")
    dbs.sql.seedTestLibraryAndFolder()
    dbs.sql.seedTestBook("book-1")
    val bus = ChangeBus()
    val registry = SyncRegistry()
    val userStatsRepo = UserStatsRepository(db = dbs.sql, bus = bus, registry = registry)
    val recorder =
        StatsRecorder(
            sql = dbs.sql,
            userStatsRepo = userStatsRepo,
            bookReadsRepository = BookReadsRepository(db = dbs.sql),
            publicProfileMaintainer =
                PublicProfileMaintainer(
                    sql = dbs.sql,
                    publicProfileRepo = PublicProfileRepository(db = dbs.sql, bus = bus, registry = registry),
                    clock = clock,
                ),
            activityRecorder = dbs.activityRecorder(bus = bus),
            statsBackfill = UserStatsBackfillService(sql = dbs.sql, userStatsRepo = userStatsRepo),
            clock = clock,
        )
    val events = ListeningEventRepository(db = dbs.sql, bus = ChangeBus(), registry = SyncRegistry())
    return RealStartFixture(dbs.sql, recorder, events, ActivityRepository(db = dbs.sql))
}
