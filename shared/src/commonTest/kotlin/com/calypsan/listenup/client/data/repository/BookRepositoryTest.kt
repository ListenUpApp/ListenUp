package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.core.BookId
import com.calypsan.listenup.client.core.ChapterId
import com.calypsan.listenup.client.core.Timestamp
import com.calypsan.listenup.client.data.local.db.AudioFileDao
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.ChapterDao
import com.calypsan.listenup.client.data.local.db.ChapterEntity
import com.calypsan.listenup.client.data.local.db.SyncState
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.sync.SyncManagerContract
import com.calypsan.listenup.client.domain.repository.ImageStorage
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.core.Failure

/**
 * Mokkery-driven seam tests for [BookRepositoryImpl].
 *
 * Coverage here is intentionally narrow: [refreshBooks] (delegate to [SyncManagerContract])
 * and [getChapters] (delegate to [ChapterDao] + chapter mapping). The list/detail
 * surfaces — `observeBookListItems`, `getBookListItem`, `getBookListItems`,
 * `observeBookDetail`, `getBookDetail` — are exercised end-to-end against an
 * in-memory Room DB in [BookRepositoryImplTest] under jvmTest.
 */
class BookRepositoryTest {
    // ========== Test Fixtures ==========

    private class TestFixture {
        val bookDao: BookDao = mock()
        val chapterDao: ChapterDao = mock()
        val audioFileDao: AudioFileDao = mock()
        val transactionRunner: TransactionRunner = mock()
        val syncManager: SyncManagerContract = mock()
        val imageStorage: ImageStorage = mock()
        val genreRepository: com.calypsan.listenup.client.domain.repository.GenreRepository = mock()
        val tagRepository: com.calypsan.listenup.client.domain.repository.TagRepository = mock()

        fun build(): BookRepositoryImpl =
            BookRepositoryImpl(
                bookDao = bookDao,
                chapterDao = chapterDao,
                audioFileDao = audioFileDao,
                transactionRunner = transactionRunner,
                syncManager = syncManager,
                imageStorage = imageStorage,
                genreRepository = genreRepository,
                tagRepository = tagRepository,
            )
    }

    private fun createFixture(): TestFixture {
        val fixture = TestFixture()
        // Default stubs for common operations
        every { fixture.bookDao.observeAllWithContributors() } returns flowOf(emptyList())
        every { fixture.imageStorage.exists(any()) } returns false
        every { fixture.imageStorage.getCoverPath(any()) } returns "/covers/default.jpg"
        return fixture
    }

    private fun createTestChapter(
        id: String = "chapter-1",
        bookId: String = "book-1",
        title: String = "Chapter 1",
        duration: Long = 1_800_000L,
        startTime: Long = 0L,
    ): ChapterEntity =
        ChapterEntity(
            id = ChapterId(id),
            bookId = BookId(bookId),
            title = title,
            duration = duration,
            startTime = startTime,
            syncState = SyncState.SYNCED,
            lastModified = Timestamp(0L),
            serverVersion = Timestamp(0L),
        )

    // ========== refreshBooks Tests ==========

    @Test
    fun `refreshBooks delegates to syncManager`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.syncManager.sync() } returns Success(Unit)
            val repository = fixture.build()

            // When
            repository.refreshBooks()

            // Then
            verifySuspend { fixture.syncManager.sync() }
        }

    @Test
    fun `refreshBooks returns success when sync succeeds`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.syncManager.sync() } returns Success(Unit)
            val repository = fixture.build()

            // When
            val result = repository.refreshBooks()

            // Then
            assertTrue(result is Success)
        }

    @Test
    fun `refreshBooks returns failure when sync fails`() =
        runTest {
            // Given
            val fixture = createFixture()
            // Body-level message convention: pass a typed AppError so the
            // user-facing message survives delegation to the repository.
            everySuspend { fixture.syncManager.sync() } returns
                Failure(com.calypsan.listenup.api.error.ValidationError(message = "Network error"))
            val repository = fixture.build()

            // When
            val result = repository.refreshBooks()

            // Then
            val failure = assertIs<Failure>(result)
            assertEquals("Network error", failure.message)
        }

    // ========== getChapters Tests ==========

    @Test
    fun `getChapters returns existing chapters from database`() =
        runTest {
            // Given
            val chapters =
                listOf(
                    createTestChapter(id = "ch-1", title = "Chapter 1", startTime = 0L),
                    createTestChapter(id = "ch-2", title = "Chapter 2", startTime = 1_800_000L),
                )
            val fixture = createFixture()
            everySuspend { fixture.chapterDao.getChaptersForBook(BookId("book-1")) } returns chapters
            val repository = fixture.build()

            // When
            val result = repository.getChapters("book-1")

            // Then
            assertEquals(2, result.size)
            assertEquals("Chapter 1", result[0].title)
            assertEquals("Chapter 2", result[1].title)
            assertEquals(0L, result[0].startTime)
            assertEquals(1_800_000L, result[1].startTime)
        }

    @Test
    fun `getChapters transforms chapter entities to domain models`() =
        runTest {
            // Given
            val chapter =
                createTestChapter(
                    id = "ch-1",
                    title = "Introduction",
                    duration = 900_000L,
                    startTime = 0L,
                )
            val fixture = createFixture()
            everySuspend { fixture.chapterDao.getChaptersForBook(BookId("book-1")) } returns listOf(chapter)
            val repository = fixture.build()

            // When
            val result = repository.getChapters("book-1")

            // Then
            assertEquals(1, result.size)
            assertEquals("ch-1", result[0].id)
            assertEquals("Introduction", result[0].title)
            assertEquals(900_000L, result[0].duration)
            assertEquals(0L, result[0].startTime)
        }
}
