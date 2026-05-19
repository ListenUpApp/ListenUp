package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.TestData
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.Success
import com.calypsan.listenup.client.data.local.db.PlaybackPositionDao
import com.calypsan.listenup.client.data.local.db.PlaybackPositionEntity
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.repository.BookRepository
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Tests for HomeRepository.
 *
 * @OptIn(ExperimentalTime::class) needed for kotlin.time.Instant usage.
 *
 * Tests cover:
 * - Continue listening list generation
 * - Progress calculation
 * - Filtering of completed books
 *
 * Uses Mokkery for mocking BookRepositoryContract and DAOs.
 */
@OptIn(ExperimentalTime::class)
class HomeRepositoryTest {
    // ========== Test Fixtures ==========

    private class TestFixture {
        val bookRepository: BookRepository = mock()
        val playbackPositionDao: PlaybackPositionDao = mock()

        fun build(): HomeRepositoryImpl =
            HomeRepositoryImpl(
                bookRepository = bookRepository,
                playbackPositionDao = playbackPositionDao,
            )
    }

    private fun createFixture(): TestFixture {
        val fixture = TestFixture()

        // Default stubs
        everySuspend { fixture.playbackPositionDao.getRecentPositions(any()) } returns emptyList()
        every { fixture.playbackPositionDao.observeRecentPositions(any()) } returns flowOf(emptyList())
        everySuspend { fixture.playbackPositionDao.get(any()) } returns null
        everySuspend { fixture.playbackPositionDao.save(any()) } returns Unit
        everySuspend { fixture.bookRepository.getBookListItems(any()) } returns emptyList()

        return fixture
    }

    // ========== Test Data Factories ==========

    private fun createPlaybackPosition(
        bookId: String,
        positionMs: Long,
        updatedAt: Long = Clock.System.now().toEpochMilliseconds(),
        isFinished: Boolean = false,
    ): PlaybackPositionEntity =
        PlaybackPositionEntity(
            bookId = BookId(bookId),
            positionMs = positionMs,
            playbackSpeed = 1.0f,
            updatedAt = updatedAt,
            isFinished = isFinished,
        )

    private fun createBook(
        id: String = "book-1",
        title: String = "Test Book",
        duration: Long = 10_000L,
        authorNames: String = "Author Name",
        coverPath: String? = null,
    ): BookListItem =
        TestData.bookListItem(
            id = id,
            title = title,
            authorName = authorNames,
            duration = duration,
            coverPath = coverPath,
        )

    // ========== Continue Listening Tests ==========

    @Test
    fun `getContinueListening returns empty list when no positions`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.playbackPositionDao.getRecentPositions(10) } returns emptyList()
            val repository = fixture.build()

            // When
            val result = repository.getContinueListening(10)

            // Then
            val success = assertIs<Success<*>>(result)
            assertTrue((success.data as List<*>).isEmpty())
        }

    @Test
    fun `getContinueListening returns books with correct progress`() =
        runTest {
            // Given
            val fixture = createFixture()
            val position = createPlaybackPosition("book-1", positionMs = 5000L)
            val book = createBook(id = "book-1", title = "Test Book", duration = 10_000L)

            everySuspend { fixture.playbackPositionDao.getRecentPositions(10) } returns listOf(position)
            everySuspend { fixture.bookRepository.getBookListItems(any()) } returns listOf(book)
            val repository = fixture.build()

            // When
            val result = repository.getContinueListening(10)

            // Then
            val success = assertIs<Success<*>>(result)
            val books = success.data as List<*>
            assertEquals(1, books.size)
            val continueBook = books[0] as com.calypsan.listenup.client.domain.model.ContinueListeningBook
            assertEquals("book-1", continueBook.bookId)
            assertEquals("Test Book", continueBook.title)
            assertEquals(0.5f, continueBook.progress)
            assertEquals(5000L, continueBook.currentPositionMs)
            assertEquals(10_000L, continueBook.totalDurationMs)
        }

    @Test
    fun `getContinueListening filters out books not found in repository`() =
        runTest {
            // Given
            val fixture = createFixture()
            val position1 = createPlaybackPosition("book-1", positionMs = 5000L)
            val position2 = createPlaybackPosition("book-2", positionMs = 3000L)
            val book1 = createBook(id = "book-1", title = "Book One", duration = 10_000L)

            everySuspend { fixture.playbackPositionDao.getRecentPositions(10) } returns listOf(position1, position2)
            // getBooks returns only book1 — book2 is absent (simulates "not found")
            everySuspend { fixture.bookRepository.getBookListItems(any()) } returns listOf(book1)
            val repository = fixture.build()

            // When
            val result = repository.getContinueListening(10)

            // Then
            val success = assertIs<Success<*>>(result)
            val books = success.data as List<*>
            assertEquals(1, books.size)
        }

    @Test
    fun `getContinueListening filters out books with isFinished true`() =
        runTest {
            // Given: A book marked as finished AND near-complete (>=95%)
            val fixture = createFixture()
            val finishedPosition = createPlaybackPosition("book-1", positionMs = 9500L, isFinished = true)
            val book = createBook(id = "book-1", duration = 10_000L)

            everySuspend { fixture.playbackPositionDao.getRecentPositions(10) } returns listOf(finishedPosition)
            everySuspend { fixture.bookRepository.getBookListItems(any()) } returns listOf(book)
            val repository = fixture.build()

            // When
            val result = repository.getContinueListening(10)

            // Then: Book should be filtered out because isFinished=true AND position>=95%
            val success = assertIs<Success<*>>(result)
            val books = success.data as List<*>
            assertTrue(books.isEmpty())
        }

    @Test
    fun `getContinueListening includes books at 99 percent progress when not marked finished`() =
        runTest {
            // Given: A book at 99% progress but NOT marked as finished
            // This tests the new behavior where isFinished is authoritative, not calculated progress
            val fixture = createFixture()
            val almostDone = createPlaybackPosition("book-1", positionMs = 9900L, isFinished = false) // 99% but not marked finished
            val book = createBook(id = "book-1", duration = 10_000L)

            everySuspend { fixture.playbackPositionDao.getRecentPositions(10) } returns listOf(almostDone)
            everySuspend { fixture.bookRepository.getBookListItems(any()) } returns listOf(book)
            val repository = fixture.build()

            // When
            val result = repository.getContinueListening(10)

            // Then: Book should be included because isFinished=false (progress doesn't matter)
            val success = assertIs<Success<*>>(result)
            val books = success.data as List<*>
            assertEquals(1, books.size)
        }

    @Test
    fun `getContinueListening includes books at 98 percent progress`() =
        runTest {
            // Given
            val fixture = createFixture()
            val position = createPlaybackPosition("book-1", positionMs = 9800L) // 98%
            val book = createBook(id = "book-1", duration = 10_000L)

            everySuspend { fixture.playbackPositionDao.getRecentPositions(10) } returns listOf(position)
            everySuspend { fixture.bookRepository.getBookListItems(any()) } returns listOf(book)
            val repository = fixture.build()

            // When
            val result = repository.getContinueListening(10)

            // Then
            val success = assertIs<Success<*>>(result)
            val books = success.data as List<*>
            assertEquals(1, books.size)
        }

    @Test
    fun `getContinueListening handles zero duration book`() =
        runTest {
            // Given
            val fixture = createFixture()
            val position = createPlaybackPosition("book-1", positionMs = 5000L)
            val book = createBook(id = "book-1", duration = 0L) // Zero duration

            everySuspend { fixture.playbackPositionDao.getRecentPositions(10) } returns listOf(position)
            everySuspend { fixture.bookRepository.getBookListItems(any()) } returns listOf(book)
            val repository = fixture.build()

            // When
            val result = repository.getContinueListening(10)

            // Then - progress should be 0, book not filtered (progress < 0.99)
            val success = assertIs<Success<*>>(result)
            val books = success.data as List<*>
            assertEquals(1, books.size)
            val continueBook = books[0] as com.calypsan.listenup.client.domain.model.ContinueListeningBook
            assertEquals(0f, continueBook.progress)
        }

    @Test
    fun `getContinueListening includes author names`() =
        runTest {
            // Given
            val fixture = createFixture()
            val position = createPlaybackPosition("book-1", positionMs = 5000L)
            val book = createBook(id = "book-1", duration = 10_000L, authorNames = "Stephen King")

            everySuspend { fixture.playbackPositionDao.getRecentPositions(10) } returns listOf(position)
            everySuspend { fixture.bookRepository.getBookListItems(any()) } returns listOf(book)
            val repository = fixture.build()

            // When
            val result = repository.getContinueListening(10)

            // Then
            val success = assertIs<Success<*>>(result)
            val books = success.data as List<*>
            val continueBook = books[0] as com.calypsan.listenup.client.domain.model.ContinueListeningBook
            assertEquals("Stephen King", continueBook.authorNames)
        }

    @Test
    fun `getContinueListening includes cover path`() =
        runTest {
            // Given
            val fixture = createFixture()
            val position = createPlaybackPosition("book-1", positionMs = 5000L)
            val book = createBook(id = "book-1", duration = 10_000L, coverPath = "/path/to/cover.jpg")

            everySuspend { fixture.playbackPositionDao.getRecentPositions(10) } returns listOf(position)
            everySuspend { fixture.bookRepository.getBookListItems(any()) } returns listOf(book)
            val repository = fixture.build()

            // When
            val result = repository.getContinueListening(10)

            // Then
            val success = assertIs<Success<*>>(result)
            val books = success.data as List<*>
            val continueBook = books[0] as com.calypsan.listenup.client.domain.model.ContinueListeningBook
            assertEquals("/path/to/cover.jpg", continueBook.coverPath)
        }

    @Test
    fun `getContinueListening respects limit parameter`() =
        runTest {
            // Given
            val fixture = createFixture()
            val positions = (1..20).map { createPlaybackPosition("book-$it", positionMs = 5000L) }
            val books = (1..20).map { createBook(id = "book-$it", duration = 10_000L) }

            everySuspend { fixture.playbackPositionDao.getRecentPositions(5) } returns positions.take(5)
            everySuspend { fixture.bookRepository.getBookListItems(any()) } returns books.take(5)
            val repository = fixture.build()

            // When
            val result = repository.getContinueListening(5)

            // Then
            val success = assertIs<Success<*>>(result)
            val returnedBooks = success.data as List<*>
            assertEquals(5, returnedBooks.size)
        }

    // ========== Regression Test: lastPlayedAt must be ISO 8601 ==========

    @Test
    fun `getContinueListening returns lastPlayedAt as ISO 8601 timestamp`() =
        runTest {
            // Given: A position with specific lastPlayedAt timestamp
            // Issue #3: lastPlayedAt was being returned as raw epoch milliseconds
            // instead of ISO 8601 format, breaking UI display
            val fixture = createFixture()
            // Jan 1, 2024 12:00:00 UTC = 1704110400000L
            val lastPlayedAtMs = 1704110400000L
            val position =
                PlaybackPositionEntity(
                    bookId = BookId("book-1"),
                    positionMs = 5000L,
                    playbackSpeed = 1.0f,
                    updatedAt = lastPlayedAtMs - 1000L, // Entity update time (different)
                    lastPlayedAt = lastPlayedAtMs, // Actual last play time
                )
            val book = createBook(id = "book-1", duration = 10_000L)

            everySuspend { fixture.playbackPositionDao.getRecentPositions(10) } returns listOf(position)
            everySuspend { fixture.bookRepository.getBookListItems(any()) } returns listOf(book)
            val repository = fixture.build()

            // When
            val result = repository.getContinueListening(10)

            // Then: lastPlayedAt should be ISO 8601, not raw milliseconds
            val success = assertIs<Success<*>>(result)
            val books = success.data as List<*>
            assertEquals(1, books.size)
            val continueBook = books[0] as com.calypsan.listenup.client.domain.model.ContinueListeningBook
            // Should be ISO 8601 format, not "1704110400000"
            assertTrue(
                continueBook.lastPlayedAt.contains("2024-01-01"),
                "Expected ISO 8601 timestamp containing '2024-01-01', got: ${continueBook.lastPlayedAt}",
            )
            assertTrue(
                continueBook.lastPlayedAt.contains("T"),
                "Expected ISO 8601 timestamp with 'T' separator, got: ${continueBook.lastPlayedAt}",
            )
        }

    @Test
    fun `getContinueListening uses updatedAt as fallback when lastPlayedAt is null`() =
        runTest {
            // Given: A position without lastPlayedAt (legacy data before migration)
            val fixture = createFixture()
            val updatedAtMs = 1704110400000L // Jan 1, 2024 12:00:00 UTC
            val position =
                PlaybackPositionEntity(
                    bookId = BookId("book-1"),
                    positionMs = 5000L,
                    playbackSpeed = 1.0f,
                    updatedAt = updatedAtMs,
                    lastPlayedAt = null, // Not set (legacy data)
                )
            val book = createBook(id = "book-1", duration = 10_000L)

            everySuspend { fixture.playbackPositionDao.getRecentPositions(10) } returns listOf(position)
            everySuspend { fixture.bookRepository.getBookListItems(any()) } returns listOf(book)
            val repository = fixture.build()

            // When
            val result = repository.getContinueListening(10)

            // Then: Should fall back to updatedAt, still as ISO 8601
            val success = assertIs<Success<*>>(result)
            val books = success.data as List<*>
            val continueBook = books[0] as com.calypsan.listenup.client.domain.model.ContinueListeningBook
            assertTrue(
                continueBook.lastPlayedAt.contains("2024-01-01"),
                "Expected ISO 8601 from updatedAt fallback, got: ${continueBook.lastPlayedAt}",
            )
        }

    // ========== Regression Tests: N+1 fix — getBooks called once, not per book ==========

    @Test
    fun `getContinueListening calls getBooks once for multiple positions, not per book`() =
        runTest {
            // Given: Three positions — verifies a single batched call replaces N per-book calls
            val fixture = createFixture()
            val positions =
                listOf(
                    createPlaybackPosition("book-1", positionMs = 1000L),
                    createPlaybackPosition("book-2", positionMs = 2000L),
                    createPlaybackPosition("book-3", positionMs = 3000L),
                )
            val books =
                listOf(
                    createBook(id = "book-1", duration = 10_000L),
                    createBook(id = "book-2", duration = 10_000L),
                    createBook(id = "book-3", duration = 10_000L),
                )

            everySuspend { fixture.playbackPositionDao.getRecentPositions(any()) } returns positions
            everySuspend { fixture.bookRepository.getBookListItems(any()) } returns books
            val repository = fixture.build()

            // When
            repository.getContinueListening(10)

            // Then: getBookListItems called exactly once (batched)
            verifySuspend(VerifyMode.exactly(1)) { fixture.bookRepository.getBookListItems(any()) }
        }

    @Test
    fun `observeContinueListening calls getBooks once per emission, not per book`() =
        runTest {
            // Given: Three positions emitted as a single list
            val fixture = createFixture()
            val positions =
                listOf(
                    createPlaybackPosition("book-1", positionMs = 1000L),
                    createPlaybackPosition("book-2", positionMs = 2000L),
                    createPlaybackPosition("book-3", positionMs = 3000L),
                )
            val books =
                listOf(
                    createBook(id = "book-1", duration = 10_000L),
                    createBook(id = "book-2", duration = 10_000L),
                    createBook(id = "book-3", duration = 10_000L),
                )

            every { fixture.playbackPositionDao.observeRecentPositions(any()) } returns flowOf(positions)
            everySuspend { fixture.bookRepository.getBookListItems(any()) } returns books
            val repository = fixture.build()

            // When: collect one emission
            repository.observeContinueListening(10).first()

            // Then: getBookListItems called exactly once (batched)
            verifySuspend(VerifyMode.exactly(1)) { fixture.bookRepository.getBookListItems(any()) }
        }
}
