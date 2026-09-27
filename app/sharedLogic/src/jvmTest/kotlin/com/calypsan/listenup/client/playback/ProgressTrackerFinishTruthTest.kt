package com.calypsan.listenup.client.playback

import com.calypsan.listenup.client.test.fake.FakePlaybackPositionRepository
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

private val BOOK = BookId("book-1")
private const val DURATION = 100_000L

/**
 * What "finished" means at the one seam every platform reaches. Android's STATE_ENDED, iOS's
 * book-ended handler and the shared player's Ended all land in [ProgressTracker.onBookFinished],
 * and every platform's pause lands in [ProgressTracker.onPlaybackPaused] — so the guard and the
 * stopped-in-the-credits rule live here, once, rather than in three call sites that disagreed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProgressTrackerFinishTruthTest :
    FunSpec({
        test("an end-of-book signal near the end marks the book finished at its full duration") {
            runTest {
                val positions = FakePlaybackPositionRepository()
                val tracker = buildProgressTracker(scope = this, positionRepository = positions)

                tracker.onBookFinished(BOOK, positionMs = 95_000L, durationMs = DURATION) shouldBe true
                advanceUntilIdle()

                val stored = positions.stored(BOOK)
                stored.isFinished shouldBe true
                stored.positionMs shouldBe DURATION
            }
        }

        test("a spurious end-of-book signal mid-book is ignored and writes nothing") {
            runTest {
                val positions = FakePlaybackPositionRepository()
                val tracker = buildProgressTracker(scope = this, positionRepository = positions)

                tracker.onBookFinished(BOOK, positionMs = 40_000L, durationMs = DURATION) shouldBe false
                advanceUntilIdle()

                positions.storedOrNull(BOOK).shouldBeNull()
            }
        }

        test("an end-of-book signal with an unknown duration is ignored") {
            runTest {
                val tracker = buildProgressTracker(scope = this)

                tracker.onBookFinished(BOOK, positionMs = 40_000L, durationMs = 0L) shouldBe false
            }
        }

        test("pausing in the end credits finishes the book") {
            runTest {
                val positions = FakePlaybackPositionRepository()
                val tracker = buildProgressTracker(scope = this, positionRepository = positions)
                tracker.onPlaybackStarted(BOOK, positionMs = 90_000L, speed = 1.0f)

                tracker.onPlaybackPaused(BOOK, positionMs = 99_500L, speed = 1.0f, durationMs = DURATION)
                advanceUntilIdle()

                positions.stored(BOOK).isFinished shouldBe true
            }
        }

        test("pausing mid-book saves the position and does not finish the book") {
            runTest {
                val positions = FakePlaybackPositionRepository()
                val tracker = buildProgressTracker(scope = this, positionRepository = positions)
                tracker.onPlaybackStarted(BOOK, positionMs = 10_000L, speed = 1.0f)

                tracker.onPlaybackPaused(BOOK, positionMs = 50_000L, speed = 1.0f, durationMs = DURATION)
                advanceUntilIdle()

                val stored = positions.stored(BOOK)
                stored.isFinished shouldBe false
                stored.positionMs shouldBe 50_000L
            }
        }

        test("starting a re-listen clears the finished flag so the new listen-through can resume") {
            runTest {
                val positions = FakePlaybackPositionRepository()
                val tracker = buildProgressTracker(scope = this, positionRepository = positions)
                tracker.onBookFinished(BOOK, positionMs = DURATION, durationMs = DURATION)
                advanceUntilIdle()

                tracker.startRelisten(BOOK)

                val stored = positions.stored(BOOK)
                stored.isFinished shouldBe false
                stored.positionMs shouldBe 0L
            }
        }
    })

private suspend fun FakePlaybackPositionRepository.storedOrNull(bookId: BookId) =
    (get(bookId) as com.calypsan.listenup.api.result.AppResult.Success).data

private suspend fun FakePlaybackPositionRepository.stored(bookId: BookId) =
    storedOrNull(bookId) ?: error("no stored position for ${bookId.value}")
