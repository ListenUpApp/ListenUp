package com.calypsan.listenup.client.test.fake

import app.cash.turbine.test
import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.Success
import com.calypsan.listenup.client.domain.repository.PlaybackUpdate
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Smoke tests for [FakePlaybackPositionRepository]. Proves the fake's write-then-read
 * semantics hold — this is the core distinction from a Mokkery mock, which has no state
 * to read back from.
 */
class FakePlaybackPositionRepositoryTest {
    @Test
    fun savePlaybackStateAndGet() =
        runTest {
            val repo = FakePlaybackPositionRepository()

            repo.savePlaybackState(
                BookId("book-1"),
                PlaybackUpdate.Position(positionMs = 5_000L, speed = 1.0f),
            )

            val result =
                assertIs<AppResult.Success<com.calypsan.listenup.client.domain.model.PlaybackPosition?>>(
                    repo.get(BookId("book-1")),
                )
            val saved = assertNotNull(result.data)
            assertEquals(5_000L, saved.positionMs)
        }

    @Test
    fun observeAllEmitsOnWrite() =
        runTest {
            val repo = FakePlaybackPositionRepository()

            repo.observeAll().test {
                val initial = awaitItem()
                assertTrue(initial.isEmpty(), "initial emission must be empty (nothing saved)")

                repo.savePlaybackState(BookId("book-1"), PlaybackUpdate.Position(positionMs = 1_000L, speed = 1.0f))

                val after = awaitItem()
                assertEquals(1, after.size)
                assertEquals(1_000L, after[BookId("book-1")]?.positionMs)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun observeAllReflectsAllBooks() =
        runTest {
            val repo = FakePlaybackPositionRepository()
            repo.savePlaybackState(BookId("book-1"), PlaybackUpdate.Position(positionMs = 100L, speed = 1.0f))
            repo.savePlaybackState(BookId("book-2"), PlaybackUpdate.Position(positionMs = 200L, speed = 1.0f))

            repo.observeAll().test {
                val all = awaitItem()
                assertEquals(2, all.size)
                assertEquals(setOf(BookId("book-1"), BookId("book-2")), all.keys)
                assertEquals(100L, all[BookId("book-1")]?.positionMs)
                assertEquals(200L, all[BookId("book-2")]?.positionMs)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun markCompleteSetsIsFinishedAndFinishedAt() =
        runTest {
            val repo = FakePlaybackPositionRepository()
            repo.savePlaybackState(BookId("book-1"), PlaybackUpdate.Position(positionMs = 1_000L, speed = 1.0f))

            val result = repo.markComplete(BookId("book-1"), startedAt = 100L, finishedAt = 999L)

            assertTrue(result is com.calypsan.listenup.core.Success)
            val after =
                assertNotNull(
                    assertIs<AppResult.Success<com.calypsan.listenup.client.domain.model.PlaybackPosition?>>(
                        repo.get(BookId("book-1")),
                    ).data,
                )
            assertTrue(after.isFinished)
            assertEquals(999L, after.finishedAtMs)
            assertEquals(100L, after.startedAtMs)
        }

    @Test
    fun discardProgressRemovesEntry() =
        runTest {
            val repo = FakePlaybackPositionRepository()
            repo.savePlaybackState(BookId("book-1"), PlaybackUpdate.Position(positionMs = 1_000L, speed = 1.0f))

            val result = repo.discardProgress(BookId("book-1"))

            assertTrue(result is com.calypsan.listenup.core.Success)
            val afterDiscard = assertIs<AppResult.Success<*>>(repo.get(BookId("book-1")))
            assertNull(afterDiscard.data)
        }

    @Test
    fun restartBookResetsPositionAndClearsFinished() =
        runTest {
            val repo = FakePlaybackPositionRepository()
            repo.savePlaybackState(BookId("book-1"), PlaybackUpdate.Position(positionMs = 10_000L, speed = 1.0f))
            repo.markComplete(BookId("book-1"))

            val result = repo.restartBook(BookId("book-1"))

            assertTrue(result is com.calypsan.listenup.core.Success)
            val after =
                assertNotNull(
                    assertIs<AppResult.Success<com.calypsan.listenup.client.domain.model.PlaybackPosition?>>(
                        repo.get(BookId("book-1")),
                    ).data,
                )
            assertEquals(0L, after.positionMs)
            assertEquals(false, after.isFinished)
            assertNull(after.finishedAtMs)
        }

    @Test
    fun getLastPlayedBookReturnsMostRecentlyPlayed() =
        runTest {
            var clock = 1_000L
            val repo = FakePlaybackPositionRepository(nowMs = { clock })
            repo.savePlaybackState(BookId("oldest"), PlaybackUpdate.Position(positionMs = 100L, speed = 1.0f))
            clock = 2_000L
            repo.savePlaybackState(BookId("newest"), PlaybackUpdate.Speed(positionMs = 5_000L, speed = 1.5f, custom = true))

            val result = repo.getLastPlayedBook()

            assertTrue(result is Success)
            val info = result.data
            assertNotNull(info)
            assertEquals("newest", info.bookId.value)
            assertEquals(5_000L, info.positionMs)
            assertEquals(1.5f, info.playbackSpeed)
        }

    @Test
    fun getLastPlayedBookReturnsNullWhenEmpty() =
        runTest {
            val repo = FakePlaybackPositionRepository()

            val result = repo.getLastPlayedBook()

            assertTrue(result is Success)
            assertNull(result.data)
        }
}
