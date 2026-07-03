package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.RecordPositionRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.PlaybackPositionEntity
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
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
    })
