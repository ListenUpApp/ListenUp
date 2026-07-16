package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.RecordPositionRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.PendingOperationV2Dao
import com.calypsan.listenup.client.data.local.db.PlaybackPositionEntity
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.client.domain.repository.PlaybackUpdate
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import com.calypsan.listenup.core.BookId
import dev.mokkery.MockMode
import dev.mokkery.answering.throws
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * Pins the fix for the offline discard/restart gap: both user-command resets
 * enqueue an Upsert of the reset row (positionMs=0, finished=false, lastPlayedAt=now)
 * so the server learns about the reset without waiting for the next playback write.
 */
class PlaybackPositionOutboxTest :
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

        fun finishedEntity(bookId: BookId) =
            playedEntity(bookId).copy(
                isFinished = true,
                finishedAt = 900L,
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

        suspend fun singleQueuedRequest(db: ListenUpDatabase): RecordPositionRequest {
            val ops = db.pendingOperationV2Dao().observePending().first()
            ops shouldHaveSize 1
            ops.single().domainName shouldBe "playback_positions"
            ops.single().opType shouldBe "upsert"
            return contractJson.decodeFromString(RecordPositionRequest.serializer(), ops.single().payload)
        }

        // A repo whose outbox enqueue always throws — the DAO insert blows up mid-transaction.
        fun failingEnqueueRepoAgainst(db: ListenUpDatabase): PlaybackPositionRepositoryImpl {
            val throwingDao =
                mock<PendingOperationV2Dao>(MockMode.autofill) {
                    everySuspend { insert(any()) } throws RuntimeException("outbox insert boom")
                }
            return PlaybackPositionRepositoryImpl(
                dao = db.playbackPositionDao(),
                transactionRunner = RoomTransactionRunner(db),
                pendingQueue = PendingOperationQueue(dao = throwingDao, sender = { AppResult.Success(Unit) }),
                authSession = FakeAuthSession(userId = "u1"),
            )
        }

        // F11: the Room merge and the outbox enqueue must commit atomically. Both these tests would
        // fail if the enqueue were a separate post-commit step (a crash between them would strand
        // the newest position local-only — catch-up is inbound-only and NewerWins shields the row).

        test("position write and outbox enqueue are atomic — a failed enqueue rolls back the local write") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val repo = failingEnqueueRepoAgainst(db)
                    val bookId = BookId("b1")
                    // Seed a known row (positionMs=90_000). The Position handler updates it in-txn, then
                    // the enqueue throws — proving atomicity requires the UPDATE to be undone.
                    db.playbackPositionDao().save(playedEntity(bookId))

                    repo
                        .savePlaybackState(bookId, PlaybackUpdate.Position(positionMs = 5_000L, speed = 1.25f))
                        .shouldBeInstanceOf<AppResult.Failure>()

                    // The whole transaction rolled back: the row keeps its pre-update position and no op queued.
                    db
                        .playbackPositionDao()
                        .get(bookId)
                        .shouldNotBeNull()
                        .positionMs shouldBe 90_000L
                    db.pendingOperationV2Dao().observePending().first() shouldHaveSize 0
                } finally {
                    db.close()
                }
            }
        }

        test("a successful save persists BOTH the local position row and the queued op") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val repo = repoAgainst(db)
                    val bookId = BookId("b1")
                    db.playbackPositionDao().save(playedEntity(bookId))

                    repo
                        .savePlaybackState(bookId, PlaybackUpdate.Position(positionMs = 5_000L, speed = 1.25f))
                        .shouldBeInstanceOf<AppResult.Success<*>>()

                    db
                        .playbackPositionDao()
                        .get(bookId)
                        .shouldNotBeNull()
                        .positionMs shouldBe 5_000L
                    db.pendingOperationV2Dao().observePending().first() shouldHaveSize 1
                } finally {
                    db.close()
                }
            }
        }

        test("discardProgress enqueues an upsert of the reset position") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val repo = repoAgainst(db)
                    val bookId = BookId("b1")
                    db.playbackPositionDao().save(playedEntity(bookId))

                    repo.discardProgress(bookId).shouldBeInstanceOf<AppResult.Success<*>>()

                    val request = singleQueuedRequest(db)
                    request.positionMs shouldBe 0L
                    request.finished shouldBe false
                } finally {
                    db.close()
                }
            }
        }

        test("restartBook enqueues an upsert of the reset position") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val repo = repoAgainst(db)
                    val bookId = BookId("b1")
                    db.playbackPositionDao().save(playedEntity(bookId))

                    repo.restartBook(bookId).shouldBeInstanceOf<AppResult.Success<*>>()

                    val request = singleQueuedRequest(db)
                    request.positionMs shouldBe 0L
                    request.finished shouldBe false
                } finally {
                    db.close()
                }
            }
        }

        test("discardProgress with no local row enqueues nothing") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val repo = repoAgainst(db)
                    repo.discardProgress(BookId("missing")).shouldBeInstanceOf<AppResult.Success<*>>()
                    db.pendingOperationV2Dao().observePending().first() shouldHaveSize 0
                } finally {
                    db.close()
                }
            }
        }

        test("PeriodicUpdate on a finished book keeps finished=true in the enqueued request") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val repo = repoAgainst(db)
                    val bookId = BookId("b1")
                    db.playbackPositionDao().save(finishedEntity(bookId))

                    repo
                        .savePlaybackState(bookId, PlaybackUpdate.PeriodicUpdate(positionMs = 5_000L, speed = 1.25f))
                        .shouldBeInstanceOf<AppResult.Success<*>>()

                    singleQueuedRequest(db).finished shouldBe true
                } finally {
                    db.close()
                }
            }
        }

        test("Position on a finished book keeps finished=true in the enqueued request") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val repo = repoAgainst(db)
                    val bookId = BookId("b1")
                    db.playbackPositionDao().save(finishedEntity(bookId))

                    repo
                        .savePlaybackState(bookId, PlaybackUpdate.Position(positionMs = 5_000L, speed = 1.25f))
                        .shouldBeInstanceOf<AppResult.Success<*>>()

                    singleQueuedRequest(db).finished shouldBe true
                } finally {
                    db.close()
                }
            }
        }

        test("PlaybackStarted on a finished book keeps finished=true in the enqueued request") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val repo = repoAgainst(db)
                    val bookId = BookId("b1")
                    db.playbackPositionDao().save(finishedEntity(bookId))

                    repo
                        .savePlaybackState(bookId, PlaybackUpdate.PlaybackStarted(positionMs = 5_000L, speed = 1.25f))
                        .shouldBeInstanceOf<AppResult.Success<*>>()

                    singleQueuedRequest(db).finished shouldBe true
                } finally {
                    db.close()
                }
            }
        }

        test("Position on an unfinished book enqueues finished=false") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val repo = repoAgainst(db)
                    val bookId = BookId("b1")
                    db.playbackPositionDao().save(playedEntity(bookId))

                    repo
                        .savePlaybackState(bookId, PlaybackUpdate.Position(positionMs = 5_000L, speed = 1.25f))
                        .shouldBeInstanceOf<AppResult.Success<*>>()

                    singleQueuedRequest(db).finished shouldBe false
                } finally {
                    db.close()
                }
            }
        }

        test("Position with no local row enqueues finished=false") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val repo = repoAgainst(db)
                    val bookId = BookId("missing")

                    repo
                        .savePlaybackState(bookId, PlaybackUpdate.Position(positionMs = 5_000L, speed = 1.25f))
                        .shouldBeInstanceOf<AppResult.Success<*>>()

                    singleQueuedRequest(db).finished shouldBe false
                } finally {
                    db.close()
                }
            }
        }

        // ──────────────────────────────────────────────────────────────────────
        // Monotonic maxPositionMs (high-water frontier) — every dao.save() handler
        // must carry max(existing?.maxPositionMs ?: 0, newPositionMs), since Room's
        // @Upsert can't express a column-wise MAX (unlike updatePositionOnly's SQL).
        // ──────────────────────────────────────────────────────────────────────

        test("Speed change raises maxPositionMs when the new position advances past the prior max") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val repo = repoAgainst(db)
                    val bookId = BookId("b1")
                    db.playbackPositionDao().save(playedEntity(bookId).copy(positionMs = 10_000L, maxPositionMs = 10_000L))

                    repo
                        .savePlaybackState(bookId, PlaybackUpdate.Speed(positionMs = 40_000L, speed = 1.5f, custom = true))
                        .shouldBeInstanceOf<AppResult.Success<*>>()

                    val row = db.playbackPositionDao().get(bookId)!!
                    row.positionMs shouldBe 40_000L
                    row.maxPositionMs shouldBe 40_000L
                } finally {
                    db.close()
                }
            }
        }

        test("Speed change does not lower maxPositionMs when the new position is behind the prior max") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val repo = repoAgainst(db)
                    val bookId = BookId("b1")
                    // A stale write racing behind a further-ahead high-water mark.
                    db.playbackPositionDao().save(playedEntity(bookId).copy(positionMs = 80_000L, maxPositionMs = 80_000L))

                    repo
                        .savePlaybackState(bookId, PlaybackUpdate.Speed(positionMs = 20_000L, speed = 1.5f, custom = true))
                        .shouldBeInstanceOf<AppResult.Success<*>>()

                    val row = db.playbackPositionDao().get(bookId)!!
                    row.positionMs shouldBe 20_000L
                    row.maxPositionMs shouldBe 80_000L
                } finally {
                    db.close()
                }
            }
        }

        test("BookFinished raises maxPositionMs to the final position") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val repo = repoAgainst(db)
                    val bookId = BookId("b1")
                    db.playbackPositionDao().save(playedEntity(bookId).copy(positionMs = 10_000L, maxPositionMs = 10_000L))

                    repo
                        .savePlaybackState(bookId, PlaybackUpdate.BookFinished(finalPositionMs = 100_000L))
                        .shouldBeInstanceOf<AppResult.Success<*>>()

                    val row = db.playbackPositionDao().get(bookId)!!
                    row.maxPositionMs shouldBe 100_000L
                } finally {
                    db.close()
                }
            }
        }

        test("DiscardProgress resets positionMs to 0 but never lowers maxPositionMs") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val repo = repoAgainst(db)
                    val bookId = BookId("b1")
                    db.playbackPositionDao().save(playedEntity(bookId).copy(positionMs = 60_000L, maxPositionMs = 60_000L))

                    repo.discardProgress(bookId).shouldBeInstanceOf<AppResult.Success<*>>()

                    val row = db.playbackPositionDao().get(bookId)!!
                    row.positionMs shouldBe 0L
                    row.maxPositionMs shouldBe 60_000L
                } finally {
                    db.close()
                }
            }
        }

        test("Restart resets positionMs to 0 but never lowers maxPositionMs") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val repo = repoAgainst(db)
                    val bookId = BookId("b1")
                    db.playbackPositionDao().save(playedEntity(bookId).copy(positionMs = 60_000L, maxPositionMs = 60_000L))

                    repo.restartBook(bookId).shouldBeInstanceOf<AppResult.Success<*>>()

                    val row = db.playbackPositionDao().get(bookId)!!
                    row.positionMs shouldBe 0L
                    row.maxPositionMs shouldBe 60_000L
                } finally {
                    db.close()
                }
            }
        }

        // ──────────────────────────────────────────────────────────────────────
        // Task 3a: the enqueued RecordPositionRequest carries the row's current
        // high-water mark — same protective idiom as the `finished` carry above.
        // ──────────────────────────────────────────────────────────────────────

        test("Position on a book below its prior max enqueues the row's current (unlowered) maxPositionMs") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val repo = repoAgainst(db)
                    val bookId = BookId("b1")
                    db.playbackPositionDao().save(playedEntity(bookId).copy(positionMs = 70_000L, maxPositionMs = 70_000L))

                    repo
                        .savePlaybackState(bookId, PlaybackUpdate.Position(positionMs = 10_000L, speed = 1.25f))
                        .shouldBeInstanceOf<AppResult.Success<*>>()

                    singleQueuedRequest(db).maxPositionMs shouldBe 70_000L
                } finally {
                    db.close()
                }
            }
        }

        test("Position advancing past the prior max enqueues the newly-raised maxPositionMs") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val repo = repoAgainst(db)
                    val bookId = BookId("b1")
                    db.playbackPositionDao().save(playedEntity(bookId).copy(positionMs = 10_000L, maxPositionMs = 10_000L))

                    repo
                        .savePlaybackState(bookId, PlaybackUpdate.Position(positionMs = 90_000L, speed = 1.25f))
                        .shouldBeInstanceOf<AppResult.Success<*>>()

                    singleQueuedRequest(db).maxPositionMs shouldBe 90_000L
                } finally {
                    db.close()
                }
            }
        }

        // ──────────────────────────────────────────────────────────────────────
        // Story World Arc 2, Task 2: the domain-level PlaybackPosition read must carry
        // maxPositionMs through toDomain() — the spoiler-safe frontier depends on it.
        // ──────────────────────────────────────────────────────────────────────

        test("stored maxPositionMs surfaces on the domain-level read") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val repo = repoAgainst(db)
                    val bookId = BookId("b1")
                    db.playbackPositionDao().save(playedEntity(bookId).copy(positionMs = 30_000L, maxPositionMs = 75_000L))

                    val position =
                        repo
                            .get(bookId)
                            .shouldBeInstanceOf<AppResult.Success<PlaybackPosition?>>()
                            .data
                            .shouldNotBeNull()

                    position.maxPositionMs shouldBe 75_000L
                } finally {
                    db.close()
                }
            }
        }
    })
