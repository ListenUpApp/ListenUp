package com.calypsan.listenup.client.playback

import app.cash.turbine.test
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.client.domain.repository.LastPlayedInfo
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.domain.repository.PlaybackUpdate
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.error.ErrorBus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

private val BOOK_ID = BookId("book-1")

/**
 * Pins [ProgressTracker]'s honest-over-silent position-save surfacing (audit finding): every
 * write path used to fold a failure into a bare `logger.warn`, so a listener could lose their
 * place with zero warning.
 *
 * The fix's noise-vs-silence design, pinned here:
 * - The 10-30s periodic tick ([ProgressTracker.onPositionUpdate]) is GATED — silent through fewer
 *   than [POSITION_SAVE_FAILURES_TO_SURFACE] consecutive failures, surfaced exactly once at the
 *   crossing (not re-surfaced on every failure after), and reset by any success.
 * - The explicit, low-frequency paths ([ProgressTracker.onPlaybackStarted],
 *   [ProgressTracker.onPlaybackPaused], [ProgressTracker.onSpeedChanged],
 *   [ProgressTracker.onSpeedReset]) surface immediately on the very first failure.
 */
class ProgressTrackerErrorSurfacingTest :
    FunSpec({
        test("onPositionUpdate stays silent through fewer than the surface threshold") {
            runTest {
                val repo = QueuedPositionRepository(failing = true)
                val errorBus = ErrorBus()
                val tracker = buildProgressTracker(scope = this, positionRepository = repo, errorBus = errorBus)

                errorBus.errors.test {
                    repeat(POSITION_SAVE_FAILURES_TO_SURFACE - 1) {
                        tracker.onPositionUpdate(BOOK_ID, positionMs = 1_000L, speed = 1.0f)
                    }
                    advanceUntilIdle()
                    expectNoEvents()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("onPositionUpdate surfaces exactly once when consecutive failures cross the threshold") {
            runTest {
                val repo = QueuedPositionRepository(failing = true)
                val errorBus = ErrorBus()
                val tracker = buildProgressTracker(scope = this, positionRepository = repo, errorBus = errorBus)

                errorBus.errors.test {
                    // One failure beyond the silent zone: the (threshold)th failure crosses it.
                    repeat(POSITION_SAVE_FAILURES_TO_SURFACE) {
                        tracker.onPositionUpdate(BOOK_ID, positionMs = 1_000L, speed = 1.0f)
                    }
                    advanceUntilIdle()
                    awaitItem() shouldBe InternalError(debugInfo = "boom")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("onPositionUpdate does not re-surface on further consecutive failures beyond the threshold") {
            runTest {
                val repo = QueuedPositionRepository(failing = true)
                val errorBus = ErrorBus()
                val tracker = buildProgressTracker(scope = this, positionRepository = repo, errorBus = errorBus)

                errorBus.errors.test {
                    repeat(POSITION_SAVE_FAILURES_TO_SURFACE + 2) {
                        tracker.onPositionUpdate(BOOK_ID, positionMs = 1_000L, speed = 1.0f)
                    }
                    advanceUntilIdle()
                    // Exactly one emission for the whole still-failing streak, not one per tick.
                    awaitItem() shouldBe InternalError(debugInfo = "boom")
                    expectNoEvents()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("a successful periodic save resets the consecutive-failure streak") {
            runTest {
                val repo = QueuedPositionRepository(failing = true)
                val errorBus = ErrorBus()
                val tracker = buildProgressTracker(scope = this, positionRepository = repo, errorBus = errorBus)

                errorBus.errors.test {
                    // Two failures — under threshold, silent.
                    repeat(POSITION_SAVE_FAILURES_TO_SURFACE - 1) {
                        tracker.onPositionUpdate(BOOK_ID, positionMs = 1_000L, speed = 1.0f)
                    }
                    advanceUntilIdle()
                    expectNoEvents()

                    // A success in between resets the streak.
                    repo.failing = false
                    tracker.onPositionUpdate(BOOK_ID, positionMs = 2_000L, speed = 1.0f)
                    advanceUntilIdle()

                    // Two more failures — still under threshold from a fresh streak, still silent.
                    repo.failing = true
                    repeat(POSITION_SAVE_FAILURES_TO_SURFACE - 1) {
                        tracker.onPositionUpdate(BOOK_ID, positionMs = 3_000L, speed = 1.0f)
                    }
                    advanceUntilIdle()
                    expectNoEvents()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("onPlaybackPaused surfaces on the very first failure") {
            runTest {
                val repo = QueuedPositionRepository(failing = true)
                val errorBus = ErrorBus()
                val tracker = buildProgressTracker(scope = this, positionRepository = repo, errorBus = errorBus)

                errorBus.errors.test {
                    tracker.onPlaybackPaused(BOOK_ID, positionMs = 1_000L, speed = 1.0f)
                    advanceUntilIdle()
                    awaitItem() shouldBe InternalError(debugInfo = "boom")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("onPlaybackStarted surfaces on the very first failure") {
            runTest {
                val repo = QueuedPositionRepository(failing = true)
                val errorBus = ErrorBus()
                val tracker = buildProgressTracker(scope = this, positionRepository = repo, errorBus = errorBus)

                errorBus.errors.test {
                    tracker.onPlaybackStarted(BOOK_ID, positionMs = 0L, speed = 1.0f)
                    advanceUntilIdle()
                    awaitItem() shouldBe InternalError(debugInfo = "boom")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("onSpeedChanged surfaces on the very first failure") {
            runTest {
                val repo = QueuedPositionRepository(failing = true)
                val errorBus = ErrorBus()
                val tracker = buildProgressTracker(scope = this, positionRepository = repo, errorBus = errorBus)

                errorBus.errors.test {
                    tracker.onSpeedChanged(BOOK_ID, positionMs = 1_000L, newSpeed = 2.0f)
                    advanceUntilIdle()
                    awaitItem() shouldBe InternalError(debugInfo = "boom")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("onSpeedReset surfaces on the very first failure") {
            runTest {
                val repo = QueuedPositionRepository(failing = true)
                val errorBus = ErrorBus()
                val tracker = buildProgressTracker(scope = this, positionRepository = repo, errorBus = errorBus)

                errorBus.errors.test {
                    tracker.onSpeedReset(BOOK_ID, positionMs = 1_000L, defaultSpeed = 1.0f)
                    advanceUntilIdle()
                    awaitItem() shouldBe InternalError(debugInfo = "boom")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })

/**
 * Hand-rolled seam fake (not a mock — Testing rubric) whose [savePlaybackState] fails with a
 * stable [InternalError] while [failing] is true, and otherwise succeeds. [failing] is a `var` so
 * a single test can flip a streak from failing to succeeding and back.
 */
private class QueuedPositionRepository(
    var failing: Boolean,
) : PlaybackPositionRepository {
    override suspend fun get(bookId: BookId): AppResult<PlaybackPosition?> = AppResult.Success(null)

    override fun observeAll(): Flow<Map<BookId, PlaybackPosition>> = flowOf(emptyMap())

    override fun observe(bookId: BookId): Flow<PlaybackPosition?> = flowOf(null)

    override suspend fun delete(bookId: BookId): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun markComplete(
        bookId: BookId,
        startedAt: Long?,
        finishedAt: Long?,
    ): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun discardProgress(bookId: BookId): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun restartBook(bookId: BookId): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun savePlaybackState(
        bookId: BookId,
        update: PlaybackUpdate,
    ): AppResult<Unit> =
        if (failing) {
            AppResult.Failure(InternalError(debugInfo = "boom"))
        } else {
            AppResult.Success(Unit)
        }

    override suspend fun getLastPlayedBook(): AppResult<LastPlayedInfo?> = AppResult.Success(null)
}
