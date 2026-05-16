package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.core.BookId
import com.calypsan.listenup.client.core.SeriesId
import com.calypsan.listenup.client.core.Timestamp
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.BookEntity
import com.calypsan.listenup.client.data.local.db.BookSeriesCrossRef
import com.calypsan.listenup.client.data.local.db.BookWithContributors
import com.calypsan.listenup.client.data.local.db.SearchDao
import com.calypsan.listenup.client.data.local.db.SeriesDao
import com.calypsan.listenup.client.data.local.db.SeriesEntity
import com.calypsan.listenup.client.data.remote.SeriesApiContract
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.data.local.db.SeriesWithBooks as SeriesWithBooksRelation
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify
import dev.mokkery.verifySuspend
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for SeriesRepositoryImpl.
 *
 * Verifies:
 * - All SeriesRepository interface methods are correctly delegated to SeriesDao
 * - SeriesEntity to Series domain model conversion
 * - Proper handling of empty results and null cases
 * - Flow emissions for reactive queries
 */
class SeriesRepositoryImplTest {
    // ========== Test Fixtures ==========

    private fun createMockDao(): SeriesDao = mock<SeriesDao>(MockMode.autoUnit)

    private fun createRepository(dao: SeriesDao): SeriesRepositoryImpl =
        SeriesRepositoryImpl(
            seriesDao = dao,
            bookDao = mock<BookDao>(MockMode.autoUnit),
            searchDao = mock<SearchDao>(MockMode.autoUnit),
            api = mock<SeriesApiContract>(),
            networkMonitor = mock<NetworkMonitor>(),
            imageStorage = mock<ImageStorage>(),
        )

    private fun createTestSeriesEntity(
        id: String = "series-1",
        name: String = "The Stormlight Archive",
        description: String? = null,
        createdAt: Long = 1000L,
        updatedAt: Long = 1000L,
    ): SeriesEntity =
        SeriesEntity(
            id =
                com.calypsan.listenup.client.core
                    .SeriesId(id),
            name = name,
            description = description,
            createdAt = Timestamp(createdAt),
            updatedAt = Timestamp(updatedAt),
        )

    // ========== observeAll Tests ==========

    @Test
    fun `observeAll returns empty list when no series exist`() =
        runTest {
            // Given
            val dao = createMockDao()
            every { dao.observeAll() } returns flowOf(emptyList())
            val repository = createRepository(dao)

            // When
            val result = repository.observeAll().first()

            // Then
            assertTrue(result.isEmpty())
        }

    @Test
    fun `observeAll transforms entities to domain models`() =
        runTest {
            // Given
            val entities =
                listOf(
                    createTestSeriesEntity(
                        id = "series-1",
                        name = "The Stormlight Archive",
                        description = "Epic fantasy series",
                    ),
                    createTestSeriesEntity(
                        id = "series-2",
                        name = "Mistborn",
                        description = "Fantasy trilogy",
                    ),
                )
            val dao = createMockDao()
            every { dao.observeAll() } returns flowOf(entities)
            val repository = createRepository(dao)

            // When
            val result = repository.observeAll().first()

            // Then
            assertEquals(2, result.size)
            assertEquals("series-1", result[0].id.value)
            assertEquals("The Stormlight Archive", result[0].name)
            assertEquals("Epic fantasy series", result[0].description)
            assertEquals("series-2", result[1].id.value)
            assertEquals("Mistborn", result[1].name)
            assertEquals("Fantasy trilogy", result[1].description)
        }

    @Test
    fun `observeAll preserves entity order from dao`() =
        runTest {
            // Given - entities ordered by name
            val entities =
                listOf(
                    createTestSeriesEntity(id = "s1", name = "Alpha Series"),
                    createTestSeriesEntity(id = "s2", name = "Beta Series"),
                    createTestSeriesEntity(id = "s3", name = "Gamma Series"),
                )
            val dao = createMockDao()
            every { dao.observeAll() } returns flowOf(entities)
            val repository = createRepository(dao)

            // When
            val result = repository.observeAll().first()

            // Then
            assertEquals("Alpha Series", result[0].name)
            assertEquals("Beta Series", result[1].name)
            assertEquals("Gamma Series", result[2].name)
        }

    @Test
    fun `observeAll delegates to dao observeAll`() =
        runTest {
            // Given
            val dao = createMockDao()
            every { dao.observeAll() } returns flowOf(emptyList())
            val repository = createRepository(dao)

            // When
            repository.observeAll().first()

            // Then
            verify { dao.observeAll() }
        }

    // ========== observeById Tests ==========

    @Test
    fun `observeById returns null when series not found`() =
        runTest {
            // Given
            val dao = createMockDao()
            every { dao.observeById("nonexistent") } returns flowOf(null)
            val repository = createRepository(dao)

            // When
            val result = repository.observeById("nonexistent").first()

            // Then
            assertNull(result)
        }

    @Test
    fun `observeById returns series when found`() =
        runTest {
            // Given
            val entity =
                createTestSeriesEntity(
                    id = "series-1",
                    name = "The Wheel of Time",
                    description = "Epic fantasy series by Robert Jordan",
                )
            val dao = createMockDao()
            every { dao.observeById("series-1") } returns flowOf(entity)
            val repository = createRepository(dao)

            // When
            val result = repository.observeById("series-1").first()

            // Then
            assertNotNull(result)
            assertEquals("series-1", result.id.value)
            assertEquals("The Wheel of Time", result.name)
            assertEquals("Epic fantasy series by Robert Jordan", result.description)
        }

    @Test
    fun `observeById transforms entity correctly`() =
        runTest {
            // Given
            val entity =
                createTestSeriesEntity(
                    id = "series-42",
                    name = "Test Series",
                    description = "Test description",
                )
            val dao = createMockDao()
            every { dao.observeById("series-42") } returns flowOf(entity)
            val repository = createRepository(dao)

            // When
            val result = repository.observeById("series-42").first()

            // Then
            assertNotNull(result)
            assertEquals("series-42", result.id.value)
            assertEquals("Test Series", result.name)
            assertEquals("Test description", result.description)
        }

    @Test
    fun `observeById delegates to dao with correct id`() =
        runTest {
            // Given
            val dao = createMockDao()
            every { dao.observeById("target-id") } returns flowOf(null)
            val repository = createRepository(dao)

            // When
            repository.observeById("target-id").first()

            // Then
            verify { dao.observeById("target-id") }
        }

    // ========== getById Tests ==========

    @Test
    fun `getById returns null when series not found`() =
        runTest {
            // Given
            val dao = createMockDao()
            everySuspend { dao.getById("nonexistent") } returns null
            val repository = createRepository(dao)

            // When
            val result = repository.getById("nonexistent")

            // Then
            assertNull(result)
        }

    @Test
    fun `getById returns series when found`() =
        runTest {
            // Given
            val entity =
                createTestSeriesEntity(
                    id = "series-1",
                    name = "Harry Potter",
                    description = "Wizarding world",
                )
            val dao = createMockDao()
            everySuspend { dao.getById("series-1") } returns entity
            val repository = createRepository(dao)

            // When
            val result = repository.getById("series-1")

            // Then
            assertNotNull(result)
            assertEquals("series-1", result.id.value)
            assertEquals("Harry Potter", result.name)
            assertEquals("Wizarding world", result.description)
        }

    @Test
    fun `getById transforms all entity fields correctly`() =
        runTest {
            // Given
            val entity =
                createTestSeriesEntity(
                    id = "complete-series",
                    name = "Complete Series Name",
                    description = "Full description here",
                )
            val dao = createMockDao()
            everySuspend { dao.getById("complete-series") } returns entity
            val repository = createRepository(dao)

            // When
            val result = repository.getById("complete-series")

            // Then
            assertNotNull(result)
            assertEquals("complete-series", result.id.value)
            assertEquals("Complete Series Name", result.name)
            assertEquals("Full description here", result.description)
        }

    @Test
    fun `getById passes correct id to dao`() =
        runTest {
            // Given
            val dao = createMockDao()
            everySuspend { dao.getById("target-id") } returns null
            val repository = createRepository(dao)

            // When
            repository.getById("target-id")

            // Then
            verifySuspend { dao.getById("target-id") }
        }

    // ========== observeByBookId Tests ==========

    @Test
    fun `observeByBookId returns null when book has no series`() =
        runTest {
            // Given
            val dao = createMockDao()
            every { dao.observeByBookId("book-1") } returns flowOf(null)
            val repository = createRepository(dao)

            // When
            val result = repository.observeByBookId("book-1").first()

            // Then
            assertNull(result)
        }

    @Test
    fun `observeByBookId returns series for book`() =
        runTest {
            // Given
            val entity =
                createTestSeriesEntity(
                    id = "series-1",
                    name = "The Cosmere",
                    description = "Shared universe",
                )
            val dao = createMockDao()
            every { dao.observeByBookId("book-1") } returns flowOf(entity)
            val repository = createRepository(dao)

            // When
            val result = repository.observeByBookId("book-1").first()

            // Then
            assertNotNull(result)
            assertEquals("series-1", result.id.value)
            assertEquals("The Cosmere", result.name)
            assertEquals("Shared universe", result.description)
        }

    @Test
    fun `observeByBookId transforms entity to domain model`() =
        runTest {
            // Given
            val entity =
                createTestSeriesEntity(
                    id = "series-42",
                    name = "Book Series",
                    description = "Description",
                )
            val dao = createMockDao()
            every { dao.observeByBookId("book-42") } returns flowOf(entity)
            val repository = createRepository(dao)

            // When
            val result = repository.observeByBookId("book-42").first()

            // Then
            assertNotNull(result)
            assertEquals("series-42", result.id.value)
            assertEquals("Book Series", result.name)
            assertEquals("Description", result.description)
        }

    @Test
    fun `observeByBookId passes correct bookId to dao`() =
        runTest {
            // Given
            val dao = createMockDao()
            every { dao.observeByBookId("my-book-id") } returns flowOf(null)
            val repository = createRepository(dao)

            // When
            repository.observeByBookId("my-book-id").first()

            // Then
            verify { dao.observeByBookId("my-book-id") }
        }

    // ========== getBookIdsForSeries Tests ==========

    @Test
    fun `getBookIdsForSeries returns empty list when series has no books`() =
        runTest {
            // Given
            val dao = createMockDao()
            everySuspend { dao.getBookIdsForSeries("series-1") } returns emptyList()
            val repository = createRepository(dao)

            // When
            val result = repository.getBookIdsForSeries("series-1")

            // Then
            assertTrue(result.isEmpty())
        }

    @Test
    fun `getBookIdsForSeries returns book IDs for series`() =
        runTest {
            // Given
            val bookIds = listOf("book-1", "book-2", "book-3")
            val dao = createMockDao()
            everySuspend { dao.getBookIdsForSeries("series-1") } returns bookIds
            val repository = createRepository(dao)

            // When
            val result = repository.getBookIdsForSeries("series-1")

            // Then
            assertEquals(3, result.size)
            assertEquals("book-1", result[0])
            assertEquals("book-2", result[1])
            assertEquals("book-3", result[2])
        }

    @Test
    fun `getBookIdsForSeries passes correct seriesId to dao`() =
        runTest {
            // Given
            val dao = createMockDao()
            everySuspend { dao.getBookIdsForSeries("specific-series-id") } returns emptyList()
            val repository = createRepository(dao)

            // When
            repository.getBookIdsForSeries("specific-series-id")

            // Then
            verifySuspend { dao.getBookIdsForSeries("specific-series-id") }
        }

    // ========== observeBookIdsForSeries Tests ==========

    @Test
    fun `observeBookIdsForSeries returns empty list when series has no books`() =
        runTest {
            // Given
            val dao = createMockDao()
            every { dao.observeBookIdsForSeries("series-1") } returns flowOf(emptyList())
            val repository = createRepository(dao)

            // When
            val result = repository.observeBookIdsForSeries("series-1").first()

            // Then
            assertTrue(result.isEmpty())
        }

    @Test
    fun `observeBookIdsForSeries returns book IDs for series`() =
        runTest {
            // Given
            val bookIds = listOf("book-a", "book-b")
            val dao = createMockDao()
            every { dao.observeBookIdsForSeries("series-1") } returns flowOf(bookIds)
            val repository = createRepository(dao)

            // When
            val result = repository.observeBookIdsForSeries("series-1").first()

            // Then
            assertEquals(2, result.size)
            assertEquals("book-a", result[0])
            assertEquals("book-b", result[1])
        }

    @Test
    fun `observeBookIdsForSeries passes correct seriesId to dao`() =
        runTest {
            // Given
            val dao = createMockDao()
            every { dao.observeBookIdsForSeries("target-series") } returns flowOf(emptyList())
            val repository = createRepository(dao)

            // When
            repository.observeBookIdsForSeries("target-series").first()

            // Then
            verify { dao.observeBookIdsForSeries("target-series") }
        }

    // ========== Entity to Domain Conversion Tests ==========

    @Test
    fun `toDomain converts all entity fields correctly`() =
        runTest {
            // Given
            val entity =
                createTestSeriesEntity(
                    id = "conversion-test",
                    name = "Full Name",
                    description = "Full description",
                )
            val dao = createMockDao()
            everySuspend { dao.getById("conversion-test") } returns entity
            val repository = createRepository(dao)

            // When
            val result = repository.getById("conversion-test")

            // Then - verify all fields are mapped
            assertNotNull(result)
            assertEquals("conversion-test", result.id.value)
            assertEquals("Full Name", result.name)
            assertEquals("Full description", result.description)
        }

    @Test
    fun `toDomain handles null optional fields`() =
        runTest {
            // Given
            val entity =
                createTestSeriesEntity(
                    id = "minimal-series",
                    name = "Minimal Series",
                    description = null,
                )
            val dao = createMockDao()
            everySuspend { dao.getById("minimal-series") } returns entity
            val repository = createRepository(dao)

            // When
            val result = repository.getById("minimal-series")

            // Then
            assertNotNull(result)
            assertEquals("minimal-series", result.id.value)
            assertEquals("Minimal Series", result.name)
            assertNull(result.description)
        }

    // ========== Multiple Items Tests ==========

    @Test
    fun `observeAll handles large number of series`() =
        runTest {
            // Given
            val entities =
                (1..100).map { i ->
                    createTestSeriesEntity(
                        id = "series-$i",
                        name = "Series $i",
                    )
                }
            val dao = createMockDao()
            every { dao.observeAll() } returns flowOf(entities)
            val repository = createRepository(dao)

            // When
            val result = repository.observeAll().first()

            // Then
            assertEquals(100, result.size)
            assertEquals("series-1", result[0].id.value)
            assertEquals("series-100", result[99].id.value)
        }

    @Test
    fun `getBookIdsForSeries handles large number of books`() =
        runTest {
            // Given
            val bookIds = (1..200).map { "book-$it" }
            val dao = createMockDao()
            everySuspend { dao.getBookIdsForSeries("large-series") } returns bookIds
            val repository = createRepository(dao)

            // When
            val result = repository.getBookIdsForSeries("large-series")

            // Then
            assertEquals(200, result.size)
            assertEquals("book-1", result[0])
            assertEquals("book-200", result[199])
        }

    // ========== Edge Cases Tests ==========

    @Test
    fun `observeAll handles series with empty description`() =
        runTest {
            // Given
            val entity =
                createTestSeriesEntity(
                    id = "series-1",
                    name = "Series Name",
                    description = "",
                )
            val dao = createMockDao()
            every { dao.observeAll() } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeAll().first()

            // Then
            assertEquals(1, result.size)
            assertEquals("", result[0].description)
        }

    @Test
    fun `observeById handles series with special characters in name`() =
        runTest {
            // Given
            val entity =
                createTestSeriesEntity(
                    id = "series-special",
                    name = "Series: The \"Special\" One (2024)",
                    description = null,
                )
            val dao = createMockDao()
            every { dao.observeById("series-special") } returns flowOf(entity)
            val repository = createRepository(dao)

            // When
            val result = repository.observeById("series-special").first()

            // Then
            assertNotNull(result)
            assertEquals("Series: The \"Special\" One (2024)", result.name)
        }

    @Test
    fun `getBookIdsForSeries returns single book ID`() =
        runTest {
            // Given
            val dao = createMockDao()
            everySuspend { dao.getBookIdsForSeries("single-book-series") } returns listOf("only-book")
            val repository = createRepository(dao)

            // When
            val result = repository.getBookIdsForSeries("single-book-series")

            // Then
            assertEquals(1, result.size)
            assertEquals("only-book", result[0])
        }

    // ========== observeAllWithBooks Tests (drift #16 closure) ==========

    private fun createRepositoryWithBookDao(
        seriesDao: SeriesDao,
        bookDao: BookDao,
    ): SeriesRepositoryImpl {
        val imageStorage = mock<ImageStorage>(MockMode.autoUnit)
        every { imageStorage.exists(any()) } returns false
        return SeriesRepositoryImpl(
            seriesDao = seriesDao,
            bookDao = bookDao,
            searchDao = mock<SearchDao>(MockMode.autoUnit),
            api = mock<SeriesApiContract>(),
            networkMonitor = mock<NetworkMonitor>(),
            imageStorage = imageStorage,
        )
    }

    private fun makeBookEntity(
        id: String,
        title: String,
    ): BookEntity =
        BookEntity(
            id = BookId(id),
            title = title,
            sortTitle = title,
            subtitle = null,
            coverHash = null,
            coverBlurHash = null,
            dominantColor = null,
            darkMutedColor = null,
            vibrantColor = null,
            totalDuration = 1_000L,
            description = null,
            publishYear = null,
            publisher = null,
            language = null,
            isbn = null,
            asin = null,
            abridged = false,
            createdAt = Timestamp(1_000L),
            updatedAt = Timestamp(1_000L),
        )

    private fun makeBookWithContributors(book: BookEntity): BookWithContributors =
        BookWithContributors(
            book = book,
            contributors = emptyList(),
            contributorRoles = emptyList(),
            series = emptyList(),
            seriesSequences = emptyList(),
        )

    @Test
    fun `observeAllWithBooks returns empty list when no series exist`() =
        runTest {
            val seriesDao = createMockDao()
            val bookDao = mock<BookDao>(MockMode.autoUnit)
            every { seriesDao.observeAllWithBooks() } returns flowOf(emptyList())
            every { bookDao.observeAllWithContributors() } returns flowOf(emptyList())
            val repository = createRepositoryWithBookDao(seriesDao, bookDao)

            val result = repository.observeAllWithBooks().first()

            assertTrue(result.isEmpty())
        }

    @Test
    fun `observeAllWithBooks joins series with books from book dao by id`() =
        runTest {
            val seriesEntity = createTestSeriesEntity(id = "series-1", name = "Stormlight")
            val book1 = makeBookEntity("book-1", "Way of Kings")
            val book2 = makeBookEntity("book-2", "Words of Radiance")
            val seriesRelation =
                SeriesWithBooksRelation(
                    series = seriesEntity,
                    books = listOf(book1, book2),
                    bookSequences =
                        listOf(
                            BookSeriesCrossRef(BookId("book-1"), SeriesId("series-1"), "1"),
                            BookSeriesCrossRef(BookId("book-2"), SeriesId("series-1"), "2"),
                        ),
                )

            val seriesDao = createMockDao()
            val bookDao = mock<BookDao>(MockMode.autoUnit)
            every { seriesDao.observeAllWithBooks() } returns flowOf(listOf(seriesRelation))
            every { bookDao.observeAllWithContributors() } returns
                flowOf(listOf(makeBookWithContributors(book1), makeBookWithContributors(book2)))
            val repository = createRepositoryWithBookDao(seriesDao, bookDao)

            val result = repository.observeAllWithBooks().first()

            assertEquals(1, result.size)
            assertEquals("series-1", result[0].series.id.value)
            assertEquals(2, result[0].books.size)
            assertEquals("book-1", result[0].books[0].id.value)
            assertEquals("Way of Kings", result[0].books[0].title)
            assertEquals("1", result[0].bookSequences["book-1"])
            assertEquals("2", result[0].bookSequences["book-2"])
        }

    @Test
    fun `observeAllWithBooks silently skips orphan book ids missing from book dao`() =
        runTest {
            // Series references two books, but bookDao only knows about one.
            val seriesEntity = createTestSeriesEntity(id = "series-1", name = "Series")
            val book1 = makeBookEntity("book-1", "Known Book")
            val orphanBook = makeBookEntity("book-orphan", "Stale Reference")
            val seriesRelation =
                SeriesWithBooksRelation(
                    series = seriesEntity,
                    books = listOf(book1, orphanBook),
                    bookSequences = emptyList(),
                )

            val seriesDao = createMockDao()
            val bookDao = mock<BookDao>(MockMode.autoUnit)
            every { seriesDao.observeAllWithBooks() } returns flowOf(listOf(seriesRelation))
            // Only book-1 is in bookDao's flow; book-orphan is missing.
            every { bookDao.observeAllWithContributors() } returns
                flowOf(listOf(makeBookWithContributors(book1)))
            val repository = createRepositoryWithBookDao(seriesDao, bookDao)

            val result = repository.observeAllWithBooks().first()

            assertEquals(1, result.size)
            assertEquals(1, result[0].books.size, "Orphan book should be silently dropped")
            assertEquals("book-1", result[0].books[0].id.value)
        }

    // ========== observeSeriesWithBooks Tests (drift #16 closure) ==========

    @Test
    fun `observeSeriesWithBooks returns null when series relation is null`() =
        runTest {
            val seriesDao = createMockDao()
            val bookDao = mock<BookDao>(MockMode.autoUnit)
            every { seriesDao.observeByIdWithBooks("missing") } returns flowOf(null)
            every { bookDao.observeBySeriesIdWithContributors("missing") } returns flowOf(emptyList())
            val repository = createRepositoryWithBookDao(seriesDao, bookDao)

            val result = repository.observeSeriesWithBooks("missing").first()

            assertNull(result)
        }

    @Test
    fun `observeSeriesWithBooks combines series relation with contributor-enriched books`() =
        runTest {
            val seriesEntity = createTestSeriesEntity(id = "series-1", name = "Cosmere")
            val book1 = makeBookEntity("book-1", "Book One")
            val book2 = makeBookEntity("book-2", "Book Two")
            val seriesRelation =
                SeriesWithBooksRelation(
                    series = seriesEntity,
                    books = listOf(book1, book2),
                    bookSequences =
                        listOf(
                            BookSeriesCrossRef(BookId("book-1"), SeriesId("series-1"), "1"),
                            BookSeriesCrossRef(BookId("book-2"), SeriesId("series-1"), "2"),
                        ),
                )

            val seriesDao = createMockDao()
            val bookDao = mock<BookDao>(MockMode.autoUnit)
            every { seriesDao.observeByIdWithBooks("series-1") } returns flowOf(seriesRelation)
            every { bookDao.observeBySeriesIdWithContributors("series-1") } returns
                flowOf(listOf(makeBookWithContributors(book1), makeBookWithContributors(book2)))
            val repository = createRepositoryWithBookDao(seriesDao, bookDao)

            val result = repository.observeSeriesWithBooks("series-1").first()

            assertNotNull(result)
            assertEquals("series-1", result.series.id.value)
            assertEquals(2, result.books.size)
            assertEquals("book-1", result.books[0].id.value)
            assertEquals("book-2", result.books[1].id.value)
            assertEquals("1", result.bookSequences["book-1"])
            assertEquals("2", result.bookSequences["book-2"])
        }
}
