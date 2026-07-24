package com.calypsan.listenup.client.test.fake

import app.cash.turbine.test
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.domain.repository.LastPlayedInfo
import com.calypsan.listenup.client.domain.repository.PlaybackUpdate
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * Smoke tests for [FakePlaybackPositionRepository]. Proves the fake's write-then-read
 * semantics hold — this is the core distinction from a Mokkery mock, which has no state
 * to read back from.
 */
class FakePlaybackPositionRepositoryTest :
    FunSpec({
        test("savePlaybackStateAndGet") {
            runTest {
                val repo = FakePlaybackPositionRepository()

                repo.savePlaybackState(
                    BookId("book-1"),
                    PlaybackUpdate.Position(positionMs = 5_000L, speed = 1.0f),
                )

                val result =
                    repo
                        .get(BookId("book-1"))
                        .shouldBeInstanceOf<AppResult.Success<com.calypsan.listenup.client.domain.model.PlaybackPosition?>>()
                val saved = result.data.shouldNotBeNull()
                saved.positionMs shouldBe 5_000L
            }
        }

        test("observeAllEmitsOnWrite") {
            runTest {
                val repo = FakePlaybackPositionRepository()

                repo.observeAll().test {
                    val initial = awaitItem()
                    withClue("initial emission must be empty (nothing saved)") { initial.isEmpty() shouldBe true }

                    repo.savePlaybackState(BookId("book-1"), PlaybackUpdate.Position(positionMs = 1_000L, speed = 1.0f))

                    val after = awaitItem()
                    after.size shouldBe 1
                    after[BookId("book-1")]?.positionMs shouldBe 1_000L
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("observeAllReflectsAllBooks") {
            runTest {
                val repo = FakePlaybackPositionRepository()
                repo.savePlaybackState(BookId("book-1"), PlaybackUpdate.Position(positionMs = 100L, speed = 1.0f))
                repo.savePlaybackState(BookId("book-2"), PlaybackUpdate.Position(positionMs = 200L, speed = 1.0f))

                repo.observeAll().test {
                    val all = awaitItem()
                    all.size shouldBe 2
                    all.keys shouldBe setOf(BookId("book-1"), BookId("book-2"))
                    all[BookId("book-1")]?.positionMs shouldBe 100L
                    all[BookId("book-2")]?.positionMs shouldBe 200L
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("markCompleteSetsIsFinishedAndFinishedAt") {
            runTest {
                val repo = FakePlaybackPositionRepository()
                repo.savePlaybackState(BookId("book-1"), PlaybackUpdate.Position(positionMs = 1_000L, speed = 1.0f))

                val result = repo.markComplete(BookId("book-1"), startedAt = 100L, finishedAt = 999L)

                (result is AppResult.Success) shouldBe true
                val after =
                    repo
                        .get(BookId("book-1"))
                        .shouldBeInstanceOf<AppResult.Success<com.calypsan.listenup.client.domain.model.PlaybackPosition?>>()
                        .data
                        .shouldNotBeNull()
                after.isFinished shouldBe true
                after.finishedAtMs shouldBe 999L
                after.startedAtMs shouldBe 100L
            }
        }

        test("discardProgressRemovesEntry") {
            runTest {
                val repo = FakePlaybackPositionRepository()
                repo.savePlaybackState(BookId("book-1"), PlaybackUpdate.Position(positionMs = 1_000L, speed = 1.0f))

                val result = repo.discardProgress(BookId("book-1"))

                (result is AppResult.Success) shouldBe true
                val afterDiscard = repo.get(BookId("book-1")).shouldBeInstanceOf<AppResult.Success<*>>()
                afterDiscard.data shouldBe null
            }
        }

        test("restartBookResetsPositionAndClearsFinished") {
            runTest {
                val repo = FakePlaybackPositionRepository()
                repo.savePlaybackState(BookId("book-1"), PlaybackUpdate.Position(positionMs = 10_000L, speed = 1.0f))
                repo.markComplete(BookId("book-1"))

                val result = repo.restartBook(BookId("book-1"))

                (result is AppResult.Success) shouldBe true
                val after =
                    repo
                        .get(BookId("book-1"))
                        .shouldBeInstanceOf<AppResult.Success<com.calypsan.listenup.client.domain.model.PlaybackPosition?>>()
                        .data
                        .shouldNotBeNull()
                after.positionMs shouldBe 0L
                after.isFinished shouldBe false
                after.finishedAtMs shouldBe null
            }
        }

        test("getLastPlayedBookReturnsMostRecentlyPlayed") {
            runTest {
                var clock = 1_000L
                val repo = FakePlaybackPositionRepository(nowMs = { clock })
                repo.savePlaybackState(BookId("oldest"), PlaybackUpdate.Position(positionMs = 100L, speed = 1.0f))
                clock = 2_000L
                repo.savePlaybackState(BookId("newest"), PlaybackUpdate.Speed(positionMs = 5_000L, speed = 1.5f, custom = true))

                val result = repo.getLastPlayedBook()

                val info = result.shouldBeInstanceOf<AppResult.Success<LastPlayedInfo?>>().data
                info.shouldNotBeNull()
                info.bookId.value shouldBe "newest"
                info.positionMs shouldBe 5_000L
                info.playbackSpeed shouldBe 1.5f
            }
        }

        test("getLastPlayedBookReturnsNullWhenEmpty") {
            runTest {
                val repo = FakePlaybackPositionRepository()

                val result = repo.getLastPlayedBook()

                result.shouldBeInstanceOf<AppResult.Success<LastPlayedInfo?>>().data shouldBe null
            }
        }
    })
