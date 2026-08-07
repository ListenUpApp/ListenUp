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
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * `lastPlayedAt` is the conflict key for the `playback_positions` domain — whichever side carries
 * the greater value wins, on this device and on the server. So writing it means claiming "this is
 * the most recent truth about where the listener is."
 *
 * Starting playback is not that claim. It is evidence the listener OPENED a book, not that they
 * heard any of it. Stamping `lastPlayedAt = now` at start made a locally-stale row instantly the
 * newest thing in the system, which is how opening a book on a second device — before its
 * playback-positions catch-up had drained — could permanently discard newer progress made
 * elsewhere. The 2026-08-07 resume-latency investigation found the synchronous server-position
 * fetch existed to defend against exactly this write.
 *
 * The claim is made by the writes that follow from real listening: the periodic save (30s in) and
 * the pause save. A start that never becomes listening — audio focus refused, app killed at once —
 * now claims nothing, which is the truth.
 *
 * A never-played book is the exception: there is no prior value to preserve and it must surface in
 * Continue Listening, so the fresh row stamps `now`.
 */
class PlaybackStartedAuthorityTest :
    FunSpec({

        val bookId = BookId("book-1")

        fun playedEntity() =
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
            return contractJson.decodeFromString(RecordPositionRequest.serializer(), ops.single().payload)
        }

        test("PlaybackStarted preserves the existing lastPlayedAt in Room") {
            runTest {
                val db = createInMemoryTestDatabase()
                db.playbackPositionDao().save(playedEntity())

                repoAgainst(db).savePlaybackState(
                    bookId = bookId,
                    update = PlaybackUpdate.PlaybackStarted(positionMs = 95_000L, speed = 1.25f),
                )

                val row = db.playbackPositionDao().get(bookId).shouldNotBeNull()
                // The position moved — we did start playing there...
                row.positionMs shouldBe 95_000L
                // ...but authority did not, because nothing has been listened to yet.
                row.lastPlayedAt shouldBe 1_000L
            }
        }

        test("PlaybackStarted pushes the preserved lastPlayedAt, so a stale row cannot outrank the server") {
            runTest {
                val db = createInMemoryTestDatabase()
                db.playbackPositionDao().save(playedEntity())

                repoAgainst(db).savePlaybackState(
                    bookId = bookId,
                    update = PlaybackUpdate.PlaybackStarted(positionMs = 95_000L, speed = 1.25f),
                )

                singleQueuedRequest(db).lastPlayedAt shouldBe 1_000L
            }
        }

        test("a never-played book stamps now, so it still surfaces in Continue Listening") {
            runTest {
                val db = createInMemoryTestDatabase()

                repoAgainst(db).savePlaybackState(
                    bookId = bookId,
                    update = PlaybackUpdate.PlaybackStarted(positionMs = 0L, speed = 1.0f),
                )

                val row = db.playbackPositionDao().get(bookId).shouldNotBeNull()
                row.lastPlayedAt.shouldNotBeNull() shouldBeGreaterThan 1_000L
            }
        }
    })
