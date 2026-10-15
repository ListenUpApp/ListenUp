package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.RecordPositionRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.PlaybackPositionEntity
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.domain.repository.PlaybackUpdate
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * S2's write: [PlaybackUpdate.DismissFinishedCard] records when the card was dismissed, queues it for every device,
 * and never stamps `lastPlayedAt` — which would count as a listening day and win conflicts it has no claim to.
 */
class PlaybackPositionDismissalTest :
    FunSpec({
        val book = BookId("b1")

        fun finishedRow() =
            PlaybackPositionEntity(
                bookId = book,
                positionMs = 9_000L,
                playbackSpeed = 1.0f,
                updatedAt = 1_000L,
                lastPlayedAt = 1_000L,
                isFinished = true,
                finishedAt = 900L,
                startedAt = 500L,
            )

        fun repoAgainst(db: ListenUpDatabase) =
            PlaybackPositionRepositoryImpl(
                dao = db.playbackPositionDao(),
                transactionRunner = RoomTransactionRunner(db),
                pendingQueue = PendingOperationQueue(dao = db.pendingOperationV2Dao(), sender = { AppResult.Success(Unit) }),
                authSession = FakeAuthSession(userId = "u1"),
            )

        suspend fun queuedRequests(db: ListenUpDatabase): List<RecordPositionRequest> =
            db
                .pendingOperationV2Dao()
                .observePending()
                .first()
                .map { contractJson.decodeFromString(RecordPositionRequest.serializer(), it.payload) }

        fun withDb(block: suspend (ListenUpDatabase) -> Unit) =
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    block(db)
                } finally {
                    db.close()
                }
            }

        test("dismissing records the time on the row and leaves lastPlayedAt and the finish alone") {
            withDb { db ->
                db.playbackPositionDao().save(finishedRow())

                repoAgainst(db)
                    .savePlaybackState(book, PlaybackUpdate.DismissFinishedCard(atMs = 5_000L))
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()

                val row = db.playbackPositionDao().get(book).shouldNotBeNull()
                row.finishedCardDismissedAt shouldBe 5_000L
                row.lastPlayedAt shouldBe 1_000L
                row.isFinished shouldBe true
                row.finishedAt shouldBe 900L
                repoAgainst(db)
                    .observe(book)
                    .first()
                    .shouldNotBeNull()
                    .finishedCardDismissedAtMs shouldBe 5_000L
            }
        }

        test("the queued write carries the dismissal and claims no newer listening") {
            withDb { db ->
                db.playbackPositionDao().save(finishedRow())

                repoAgainst(db).savePlaybackState(book, PlaybackUpdate.DismissFinishedCard(atMs = 5_000L))

                val request = queuedRequests(db).also { it shouldHaveSize 1 }.single()
                request.finishedCardDismissedAt shouldBe 5_000L
                request.lastPlayedAt shouldBe 1_000L
                request.finished shouldBe true
                request.finishedAt shouldBe 900L
            }
        }

        test("a later position write still carries the dismissal, so coalescing cannot drop it") {
            withDb { db ->
                db.playbackPositionDao().save(finishedRow())
                val repo = repoAgainst(db)

                repo.savePlaybackState(book, PlaybackUpdate.DismissFinishedCard(atMs = 5_000L))
                repo.savePlaybackState(book, PlaybackUpdate.Position(positionMs = 9_100L, speed = 1.0f))

                queuedRequests(db).single().finishedCardDismissedAt shouldBe 5_000L
            }
        }

        test("an older dismissal never lowers a newer one") {
            withDb { db ->
                db.playbackPositionDao().save(finishedRow())
                val repo = repoAgainst(db)

                repo.savePlaybackState(book, PlaybackUpdate.DismissFinishedCard(atMs = 5_000L))
                repo.savePlaybackState(book, PlaybackUpdate.DismissFinishedCard(atMs = 4_000L))

                db
                    .playbackPositionDao()
                    .get(book)
                    .shouldNotBeNull()
                    .finishedCardDismissedAt shouldBe 5_000L
            }
        }

        test("dismissing a book with no position writes nothing and queues nothing") {
            withDb { db ->
                repoAgainst(db)
                    .savePlaybackState(book, PlaybackUpdate.DismissFinishedCard(atMs = 5_000L))
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()

                db.playbackPositionDao().get(book).shouldBeNull()
                queuedRequests(db).shouldBeEmpty()
            }
        }

        test("a restart clears the finish but keeps the dismissal, so a re-finish is newer than it") {
            withDb { db ->
                db.playbackPositionDao().save(finishedRow())
                val repo = repoAgainst(db)
                repo.savePlaybackState(book, PlaybackUpdate.DismissFinishedCard(atMs = 5_000L))

                repo.restartBook(book)
                db.playbackPositionDao().get(book).shouldNotBeNull().let { row ->
                    row.finishedAt.shouldBeNull()
                    row.finishedCardDismissedAt shouldBe 5_000L
                }

                repo.savePlaybackState(book, PlaybackUpdate.BookFinished(finalPositionMs = 9_000L))
                db
                    .playbackPositionDao()
                    .get(book)
                    .shouldNotBeNull()
                    .finishedAt
                    .shouldNotBeNull() shouldBeGreaterThan 5_000L
            }
        }
    })
