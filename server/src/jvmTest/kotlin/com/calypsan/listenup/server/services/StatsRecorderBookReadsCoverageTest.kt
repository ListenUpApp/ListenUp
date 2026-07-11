@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.ListeningEventSyncPayload
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.PublicProfileRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.activityRecorder
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * Spec 004: `book_reads` is the single finished-books primitive, and a completion becomes a new read
 * only when real content was covered since the previous finish (the coverage rule). A "finished, then
 * rewound to the last chapter, then finished again" replay must not double-count; a genuine re-read
 * (≥ 50% of the book covered since the last finish) must. A crash that left a finished position without
 * a `book_reads` row is reconciled by a rebuild.
 */
class StatsRecorderBookReadsCoverageTest :
    FunSpec({

        val day0Ms = 1_779_451_200_000L
        val oneHourMs = 3_600_000L

        fun eventOnBook(
            id: String,
            bookId: String,
            endedAtMs: Long,
            startPositionMs: Long,
            endPositionMs: Long,
        ): ListeningEventSyncPayload =
            ListeningEventSyncPayload(
                id = id,
                bookId = bookId,
                startPositionMs = startPositionMs,
                endPositionMs = endPositionMs,
                startedAt = endedAtMs - 60_000L,
                endedAt = endedAtMs,
                playbackSpeed = 1.0f,
                tz = "UTC",
                deviceLabel = null,
                revision = 0L,
                updatedAt = 0L,
                createdAt = 0L,
                deletedAt = null,
            )

        fun SqlTestDatabases.recorder(clock: FixedClock): Triple<StatsRecorder, BookReadsRepository, UserStatsRepository> {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            val userStatsRepo = UserStatsRepository(db = sql, bus = bus, registry = registry)
            val bookReads = BookReadsRepository(db = sql, clock = clock)
            val recorder =
                StatsRecorder(
                    sql = sql,
                    userStatsRepo = userStatsRepo,
                    bookReadsRepository = bookReads,
                    publicProfileMaintainer =
                        PublicProfileMaintainer(
                            sql = sql,
                            publicProfileRepo = PublicProfileRepository(db = sql, bus = bus, registry = registry),
                            clock = clock,
                        ),
                    activityRecorder = activityRecorder(bus = bus),
                    statsBackfill = UserStatsBackfillService(sql = sql, userStatsRepo = userStatsRepo, clock = clock),
                    clock = clock,
                )
            return Triple(recorder, bookReads, userStatsRepo)
        }

        test("finish then rewind the last chapter and finish again records ONE read, booksFinished stays 1") {
            withSqlDatabase {
                sql.seedTestUser("u1")
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("b1")
                sql.booksQueries.updateTotalDuration(total_duration = oneHourMs, id = "b1")
                val clock = FixedClock(Instant.fromEpochMilliseconds(day0Ms + 60_000L))
                val (recorder, bookReads, userStatsRepo) = recorder(clock)
                val eventRepo = ListeningEventRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())

                runTest {
                    // First finish — always a new read.
                    recorder.record(
                        StatsEvent.BookCompleted("u1", "b1", Instant.fromEpochMilliseconds(day0Ms)),
                    )

                    // A short replay covering only the last 10 minutes (< 50% of the 1h book).
                    eventRepo.upsert(
                        eventOnBook("rewind", "b1", endedAtMs = day0Ms + 10_000L, startPositionMs = 3_000_000L, endPositionMs = 3_600_000L),
                        clientOpId = null,
                        userId = "u1",
                    )
                    recorder.record(
                        StatsEvent.BookCompleted("u1", "b1", Instant.fromEpochMilliseconds(day0Ms + 20_000L)),
                    )

                    bookReads.finishesForUserBook("u1", "b1") shouldHaveSize 1
                    userStatsRepo.getForUser("u1").shouldNotBeNull().booksFinished shouldBe 1
                }
            }
        }

        test("finish then genuinely re-read (>=50% covered) records TWO reads, booksFinished 2") {
            withSqlDatabase {
                sql.seedTestUser("u1")
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("b1")
                sql.booksQueries.updateTotalDuration(total_duration = oneHourMs, id = "b1")
                val clock = FixedClock(Instant.fromEpochMilliseconds(day0Ms + 60_000L))
                val (recorder, bookReads, userStatsRepo) = recorder(clock)
                val eventRepo = ListeningEventRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())

                runTest {
                    recorder.record(
                        StatsEvent.BookCompleted("u1", "b1", Instant.fromEpochMilliseconds(day0Ms)),
                    )

                    // A replay covering 2,000,000 ms (> 50% of the 1h = 1,800,000 ms threshold).
                    eventRepo.upsert(
                        eventOnBook("reread", "b1", endedAtMs = day0Ms + 10_000L, startPositionMs = 0L, endPositionMs = 2_000_000L),
                        clientOpId = null,
                        userId = "u1",
                    )
                    recorder.record(
                        StatsEvent.BookCompleted("u1", "b1", Instant.fromEpochMilliseconds(day0Ms + 20_000L)),
                    )

                    bookReads.finishesForUserBook("u1", "b1") shouldHaveSize 2
                    userStatsRepo.getForUser("u1").shouldNotBeNull().booksFinished shouldBe 2
                }
            }
        }

        test("BulkRecompute reconciles a finished position with no book_reads row, idempotently") {
            withSqlDatabase {
                sql.seedTestUser("u1")
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("b2")
                val clock = FixedClock(Instant.fromEpochMilliseconds(day0Ms + 60_000L))
                val (recorder, bookReads, _) = recorder(clock)

                // A finished position that never produced a book_reads row (crash between the position
                // commit and the completion cascade, or a pre-Spec-004 import).
                sql.insertFinishedPosition(userId = "u1", bookId = "b2", lastPlayedAt = day0Ms)

                runTest {
                    bookReads.finishesForUserBook("u1", "b2") shouldHaveSize 0

                    recorder.record(StatsEvent.BulkRecompute("u1"))
                    bookReads.finishesForUserBook("u1", "b2") shouldHaveSize 1

                    // Running the rebuild again must not add a duplicate.
                    recorder.record(StatsEvent.BulkRecompute("u1"))
                    bookReads.finishesForUserBook("u1", "b2") shouldHaveSize 1
                }
            }
        }
    })

private fun ListenUpDatabase.insertFinishedPosition(
    userId: String,
    bookId: String,
    lastPlayedAt: Long,
) {
    transaction {
        playbackPositionsQueries.insert(
            id = "pos-$userId-$bookId",
            user_id = userId,
            book_id = bookId,
            position_ms = 3_600_000L,
            max_position_ms = 3_600_000L,
            last_played_at = lastPlayedAt,
            finished = 1L,
            playback_speed = 1.0,
            current_chapter_id = null,
            revision = 0L,
            created_at = 0L,
            updated_at = 0L,
            deleted_at = null,
            client_op_id = null,
        )
    }
}
