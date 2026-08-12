package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.PlaybackPositionEntity
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.domain.repository.PlaybackUpdate
import com.calypsan.listenup.client.playback.ProgressTracker
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import com.calypsan.listenup.core.BookId
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Pins three playback-position-integrity fixes against a real in-memory Room DB and a real
 * [PlaybackPositionRepositoryImpl] (modeled on [PlaybackPositionOutboxTest]):
 *
 * - C-C06: [ProgressTracker.onBookFinished] must persist the true final position and stay
 *   finished, not the up-to-30s-stale value [PlaybackPositionRepository.markComplete] left behind.
 * - C-C05: a position-only write against a book with no prior row must create the row locally,
 *   not silently no-op while still enqueuing an outbox push (Room/server would otherwise diverge).
 * - C-C04: a position-only server tombstone must not permanently exclude a book from
 *   resume/Continue-Listening or the streak — an active local write must heal it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackPositionFinishTest :
    FunSpec({

        fun playedEntity(bookId: BookId) =
            PlaybackPositionEntity(
                bookId = bookId,
                positionMs = 90_000L,
                playbackSpeed = 1.25f,
                hasCustomSpeed = true,
                updatedAt = 1_000L,
                syncedAt = 1_000L,
                lastPlayedAt = 1_000L,
                isFinished = false,
                finishedAt = null,
                startedAt = 500L,
            )

        fun repoAgainst(db: ListenUpDatabase): PlaybackPositionRepositoryImpl =
            PlaybackPositionRepositoryImpl(
                dao = db.playbackPositionDao(),
                transactionRunner = RoomTransactionRunner(db),
                pendingQueue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = { AppResult.Success(Unit) },
                    ),
                authSession = FakeAuthSession(userId = "u1"),
            )

        // ──────────────────────────────────────────────────────────────────
        // C-C06 — onBookFinished must persist the true final position
        // ──────────────────────────────────────────────────────────────────

        context("C-C06: onBookFinished") {
            test("persists the final position and marks the book finished") {
                val db = createInMemoryTestDatabase()
                try {
                    val dispatcher = StandardTestDispatcher()
                    runTest(dispatcher) {
                        val repo = repoAgainst(db)
                        val bookId = BookId("b1")
                        db.playbackPositionDao().save(playedEntity(bookId))

                        val tracker =
                            ProgressTracker(
                                downloadRepository = mock(),
                                positionRepository = repo,
                                scope = CoroutineScope(dispatcher),
                            )

                        tracker.onBookFinished(bookId, finalPositionMs = 123_000L)
                        advanceUntilIdle()

                        val stored = db.playbackPositionDao().get(bookId).shouldNotBeNull()
                        stored.positionMs shouldBe 123_000L
                        stored.isFinished shouldBe true
                    }
                } finally {
                    db.close()
                }
            }

            test("re-finishing a book keeps the original finishedAt but updates the position") {
                val db = createInMemoryTestDatabase()
                try {
                    val dispatcher = StandardTestDispatcher()
                    runTest(dispatcher) {
                        val repo = repoAgainst(db)
                        val bookId = BookId("b1")
                        db.playbackPositionDao().save(playedEntity(bookId))

                        val tracker =
                            ProgressTracker(
                                downloadRepository = mock(),
                                positionRepository = repo,
                                scope = CoroutineScope(dispatcher),
                            )

                        tracker.onBookFinished(bookId, finalPositionMs = 100_000L)
                        advanceUntilIdle()
                        val first = db.playbackPositionDao().get(bookId).shouldNotBeNull()
                        val firstFinishedAt = first.finishedAt.shouldNotBeNull()

                        tracker.onBookFinished(bookId, finalPositionMs = 150_000L)
                        advanceUntilIdle()
                        val second = db.playbackPositionDao().get(bookId).shouldNotBeNull()

                        second.finishedAt shouldBe firstFinishedAt
                        second.positionMs shouldBe 150_000L
                    }
                } finally {
                    db.close()
                }
            }
        }

        // ──────────────────────────────────────────────────────────────────
        // C-C05 — position-only writes against a book with no prior row
        // ──────────────────────────────────────────────────────────────────

        context("C-C05: insert-if-zero fallback") {
            test("Position write against a book with no prior row creates the row") {
                val db = createInMemoryTestDatabase()
                try {
                    runTest {
                        val repo = repoAgainst(db)
                        val bookId = BookId("new-book")

                        repo
                            .savePlaybackState(bookId, PlaybackUpdate.Position(positionMs = 42_000L, speed = 1.0f))
                            .shouldBeInstanceOf<AppResult.Success<*>>()

                        val stored = db.playbackPositionDao().get(bookId).shouldNotBeNull()
                        stored.positionMs shouldBe 42_000L
                    }
                } finally {
                    db.close()
                }
            }

            test("PeriodicUpdate write against a book with no prior row creates the row") {
                val db = createInMemoryTestDatabase()
                try {
                    runTest {
                        val repo = repoAgainst(db)
                        val bookId = BookId("new-book")

                        repo
                            .savePlaybackState(
                                bookId,
                                PlaybackUpdate.PeriodicUpdate(positionMs = 55_000L, speed = 1.0f),
                            ).shouldBeInstanceOf<AppResult.Success<*>>()

                        val stored = db.playbackPositionDao().get(bookId).shouldNotBeNull()
                        stored.positionMs shouldBe 55_000L
                    }
                } finally {
                    db.close()
                }
            }

            test("PlaybackPaused write against a book with no prior row creates the row") {
                val db = createInMemoryTestDatabase()
                try {
                    runTest {
                        val repo = repoAgainst(db)
                        val bookId = BookId("new-book")

                        repo
                            .savePlaybackState(
                                bookId,
                                PlaybackUpdate.PlaybackPaused(positionMs = 61_000L, speed = 1.0f),
                            ).shouldBeInstanceOf<AppResult.Success<*>>()

                        val stored = db.playbackPositionDao().get(bookId).shouldNotBeNull()
                        stored.positionMs shouldBe 61_000L
                    }
                } finally {
                    db.close()
                }
            }

            test("Position write against an existing row still preserves speed/boost (no clobber)") {
                val db = createInMemoryTestDatabase()
                try {
                    runTest {
                        val repo = repoAgainst(db)
                        val bookId = BookId("b1")
                        db.playbackPositionDao().save(playedEntity(bookId))

                        repo
                            .savePlaybackState(bookId, PlaybackUpdate.Position(positionMs = 5_000L, speed = 99f))
                            .shouldBeInstanceOf<AppResult.Success<*>>()

                        val stored = db.playbackPositionDao().get(bookId).shouldNotBeNull()
                        stored.positionMs shouldBe 5_000L
                        // updatePositionOnly never touches speed — the seeded 1.25f/custom=true survive.
                        stored.playbackSpeed shouldBe 1.25f
                        stored.hasCustomSpeed shouldBe true
                    }
                } finally {
                    db.close()
                }
            }
        }

        // ──────────────────────────────────────────────────────────────────
        // C-C04 — position-only tombstone must heal on an active local write
        // ──────────────────────────────────────────────────────────────────

        context("C-C04: tombstone-then-replay") {
            test(
                "a tombstoned position is excluded from get/getRecentPositions/streak, " +
                    "then heals on an active local write",
            ) {
                val db = createInMemoryTestDatabase()
                try {
                    runTest {
                        val repo = repoAgainst(db)
                        val bookId = BookId("b1")
                        db.playbackPositionDao().save(playedEntity(bookId))
                        db.playbackPositionDao().softDelete(bookId, deletedAt = 2_000L, revision = 5L)

                        // Tombstoned: excluded from resume/Continue-Listening reads AND the streak.
                        // getLive is the resume-facing read (dao.get stays unfiltered — it's also
                        // read by write-handler merges and the sync conflict check, which must see
                        // the raw row regardless of tombstone status; see PlaybackPositionDao.get's
                        // KDoc).
                        db.playbackPositionDao().getLive(bookId) shouldBe null
                        db.playbackPositionDao().getRecentPositions(10) shouldBe emptyList()
                        db.playbackPositionDao().observeListenedDayTimestamps().first() shouldBe emptyList()

                        // An active local write heals it.
                        repo
                            .savePlaybackState(bookId, PlaybackUpdate.Position(positionMs = 5_000L, speed = 1.0f))
                            .shouldBeInstanceOf<AppResult.Success<*>>()

                        val healed = db.playbackPositionDao().getLive(bookId).shouldNotBeNull()
                        healed.positionMs shouldBe 5_000L
                        healed.deletedAt shouldBe null

                        db.playbackPositionDao().getRecentPositions(10).map { it.bookId } shouldContain bookId
                        db
                            .playbackPositionDao()
                            .observeListenedDayTimestamps()
                            .first()
                            .shouldNotBeEmpty()
                    }
                } finally {
                    db.close()
                }
            }

            test("a tombstoned book-finish also heals the tombstone and preserves other fields") {
                val db = createInMemoryTestDatabase()
                try {
                    val dispatcher = StandardTestDispatcher()
                    runTest(dispatcher) {
                        val repo = repoAgainst(db)
                        val bookId = BookId("b1")
                        db.playbackPositionDao().save(playedEntity(bookId).copy(volumeBoostDb = 4f, hasCustomBoost = true))
                        db.playbackPositionDao().softDelete(bookId, deletedAt = 2_000L, revision = 5L)

                        val tracker =
                            ProgressTracker(
                                downloadRepository = mock(),
                                positionRepository = repo,
                                scope = CoroutineScope(dispatcher),
                            )

                        tracker.onBookFinished(bookId, finalPositionMs = 200_000L)
                        advanceUntilIdle()

                        val healed = db.playbackPositionDao().get(bookId).shouldNotBeNull()
                        healed.positionMs shouldBe 200_000L
                        healed.isFinished shouldBe true
                        healed.deletedAt shouldBe null
                        // The tombstone-clearing merge must not wipe fields the finish write
                        // doesn't own — volumeBoostDb/hasCustomBoost survive from the seeded row.
                        healed.volumeBoostDb shouldBe 4f
                        healed.hasCustomBoost shouldBe true
                    }
                } finally {
                    db.close()
                }
            }
        }
    })
