package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.data.local.db.GenreDao
import com.calypsan.listenup.client.data.local.db.GenreEntity
import com.calypsan.listenup.client.data.remote.GenreApiContract
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.mock
import dev.mokkery.verify
import dev.mokkery.verifySuspend
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for GenreRepositoryImpl.
 *
 * Verifies:
 * - All GenreRepository interface methods are correctly delegated to GenreDao
 * - GenreEntity to Genre domain model conversion
 * - Proper handling of empty results and null cases
 * - Flow emissions for reactive queries
 */
class GenreRepositoryImplTest {
    // ========== Test Fixtures ==========

    private fun createMockDao(): GenreDao = mock<GenreDao>(MockMode.autoUnit)

    private fun createMockApi(): GenreApiContract = mock<GenreApiContract>()

    private fun createRepository(
        dao: GenreDao,
        genreApi: GenreApiContract = createMockApi(),
    ): GenreRepositoryImpl = GenreRepositoryImpl(dao, genreApi)

    private fun createTestGenreEntity(
        id: String = "genre-1",
        name: String = "Epic Fantasy",
        slug: String = "epic-fantasy",
        path: String = "/fiction/fantasy/epic-fantasy",
        bookCount: Int = 42,
        parentId: String? = "genre-parent",
        depth: Int = 2,
        sortOrder: Int = 1,
    ): GenreEntity =
        GenreEntity(
            id = id,
            name = name,
            slug = slug,
            path = path,
            bookCount = bookCount,
            parentId = parentId,
            depth = depth,
            sortOrder = sortOrder,
        )

    // ========== observeAll Tests ==========

    @Test
    fun `observeAll returns empty list when no genres exist`() =
        runTest {
            // Given
            val dao = createMockDao()
            every { dao.observeAllGenres() } returns flowOf(emptyList())
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
            val genreEntity =
                createTestGenreEntity(
                    id = "genre-1",
                    name = "Science Fiction",
                    slug = "science-fiction",
                    path = "/fiction/science-fiction",
                    bookCount = 100,
                )
            val dao = createMockDao()
            every { dao.observeAllGenres() } returns flowOf(listOf(genreEntity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeAll().first()

            // Then
            assertEquals(1, result.size)
            val genre = result.first()
            assertEquals("genre-1", genre.id)
            assertEquals("Science Fiction", genre.name)
            assertEquals("science-fiction", genre.slug)
            assertEquals("/fiction/science-fiction", genre.path)
            assertEquals(100, genre.bookCount)
        }

    @Test
    fun `observeAll returns multiple genres in order`() =
        runTest {
            // Given
            val genres =
                listOf(
                    createTestGenreEntity(id = "g1", name = "Fiction", slug = "fiction", path = "/fiction"),
                    createTestGenreEntity(id = "g2", name = "Fantasy", slug = "fantasy", path = "/fiction/fantasy"),
                    createTestGenreEntity(id = "g3", name = "Epic Fantasy", slug = "epic-fantasy", path = "/fiction/fantasy/epic-fantasy"),
                )
            val dao = createMockDao()
            every { dao.observeAllGenres() } returns flowOf(genres)
            val repository = createRepository(dao)

            // When
            val result = repository.observeAll().first()

            // Then
            assertEquals(3, result.size)
            assertEquals("Fiction", result[0].name)
            assertEquals("Fantasy", result[1].name)
            assertEquals("Epic Fantasy", result[2].name)
        }

    @Test
    fun `observeAll delegates to dao observeAllGenres`() =
        runTest {
            // Given
            val dao = createMockDao()
            every { dao.observeAllGenres() } returns flowOf(emptyList())
            val repository = createRepository(dao)

            // When
            repository.observeAll().first()

            // Then
            verify { dao.observeAllGenres() }
        }

    // ========== getAll Tests ==========

    @Test
    fun `getAll returns empty list when no genres exist`() =
        runTest {
            // Given
            val dao = createMockDao()
            everySuspend { dao.getAllGenres() } returns emptyList()
            val repository = createRepository(dao)

            // When
            val result = repository.getAll()

            // Then
            assertTrue(result.isEmpty())
        }

    @Test
    fun `getAll transforms entities to domain models`() =
        runTest {
            // Given
            val genreEntity =
                createTestGenreEntity(
                    id = "genre-2",
                    name = "Mystery",
                    slug = "mystery",
                    path = "/fiction/mystery",
                    bookCount = 50,
                )
            val dao = createMockDao()
            everySuspend { dao.getAllGenres() } returns listOf(genreEntity)
            val repository = createRepository(dao)

            // When
            val result = repository.getAll()

            // Then
            assertEquals(1, result.size)
            val genre = result.first()
            assertEquals("genre-2", genre.id)
            assertEquals("Mystery", genre.name)
            assertEquals("mystery", genre.slug)
            assertEquals("/fiction/mystery", genre.path)
            assertEquals(50, genre.bookCount)
        }

    @Test
    fun `getAll returns all genres`() =
        runTest {
            // Given
            val genres =
                listOf(
                    createTestGenreEntity(id = "g1", name = "Non-Fiction", slug = "non-fiction", path = "/non-fiction"),
                    createTestGenreEntity(id = "g2", name = "Biography", slug = "biography", path = "/non-fiction/biography"),
                )
            val dao = createMockDao()
            everySuspend { dao.getAllGenres() } returns genres
            val repository = createRepository(dao)

            // When
            val result = repository.getAll()

            // Then
            assertEquals(2, result.size)
            assertEquals("Non-Fiction", result[0].name)
            assertEquals("Biography", result[1].name)
        }

    @Test
    fun `getAll delegates to dao getAllGenres`() =
        runTest {
            // Given
            val dao = createMockDao()
            everySuspend { dao.getAllGenres() } returns emptyList()
            val repository = createRepository(dao)

            // When
            repository.getAll()

            // Then
            verifySuspend { dao.getAllGenres() }
        }

    // ========== getById Tests ==========

    @Test
    fun `getById returns null when genre not found`() =
        runTest {
            // Given
            val dao = createMockDao()
            everySuspend { dao.getById("nonexistent-id") } returns null
            val repository = createRepository(dao)

            // When
            val result = repository.getById("nonexistent-id")

            // Then
            assertNull(result)
        }

    @Test
    fun `getById returns domain model when genre found`() =
        runTest {
            // Given
            val genreEntity =
                createTestGenreEntity(
                    id = "genre-3",
                    name = "Horror",
                    slug = "horror",
                    path = "/fiction/horror",
                    bookCount = 25,
                )
            val dao = createMockDao()
            everySuspend { dao.getById("genre-3") } returns genreEntity
            val repository = createRepository(dao)

            // When
            val result = repository.getById("genre-3")

            // Then
            assertEquals("genre-3", result?.id)
            assertEquals("Horror", result?.name)
            assertEquals("horror", result?.slug)
            assertEquals("/fiction/horror", result?.path)
            assertEquals(25, result?.bookCount)
        }

    @Test
    fun `getById passes correct id to dao`() =
        runTest {
            // Given
            val dao = createMockDao()
            everySuspend { dao.getById("target-genre-id") } returns null
            val repository = createRepository(dao)

            // When
            repository.getById("target-genre-id")

            // Then
            verifySuspend { dao.getById("target-genre-id") }
        }

    // ========== getBySlug Tests ==========

    @Test
    fun `getBySlug returns null when genre not found`() =
        runTest {
            // Given
            val dao = createMockDao()
            everySuspend { dao.getBySlug("nonexistent-slug") } returns null
            val repository = createRepository(dao)

            // When
            val result = repository.getBySlug("nonexistent-slug")

            // Then
            assertNull(result)
        }

    @Test
    fun `getBySlug returns domain model when genre found`() =
        runTest {
            // Given
            val genreEntity =
                createTestGenreEntity(
                    id = "genre-4",
                    name = "Thriller",
                    slug = "thriller",
                    path = "/fiction/thriller",
                    bookCount = 75,
                )
            val dao = createMockDao()
            everySuspend { dao.getBySlug("thriller") } returns genreEntity
            val repository = createRepository(dao)

            // When
            val result = repository.getBySlug("thriller")

            // Then
            assertEquals("genre-4", result?.id)
            assertEquals("Thriller", result?.name)
            assertEquals("thriller", result?.slug)
            assertEquals("/fiction/thriller", result?.path)
            assertEquals(75, result?.bookCount)
        }

    @Test
    fun `getBySlug passes correct slug to dao`() =
        runTest {
            // Given
            val dao = createMockDao()
            everySuspend { dao.getBySlug("target-slug") } returns null
            val repository = createRepository(dao)

            // When
            repository.getBySlug("target-slug")

            // Then
            verifySuspend { dao.getBySlug("target-slug") }
        }

    // ========== observeGenresForBook Tests ==========

    @Test
    fun `observeGenresForBook returns empty list when book has no genres`() =
        runTest {
            // Given
            val dao = createMockDao()
            every { dao.observeGenresForBook(BookId("book-1")) } returns flowOf(emptyList())
            val repository = createRepository(dao)

            // When
            val result = repository.observeGenresForBook("book-1").first()

            // Then
            assertTrue(result.isEmpty())
        }

    @Test
    fun `observeGenresForBook transforms entities to domain models`() =
        runTest {
            // Given
            val genreEntity =
                createTestGenreEntity(
                    id = "genre-5",
                    name = "Romance",
                    slug = "romance",
                    path = "/fiction/romance",
                    bookCount = 200,
                )
            val dao = createMockDao()
            every { dao.observeGenresForBook(BookId("book-1")) } returns flowOf(listOf(genreEntity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeGenresForBook("book-1").first()

            // Then
            assertEquals(1, result.size)
            val genre = result.first()
            assertEquals("genre-5", genre.id)
            assertEquals("Romance", genre.name)
            assertEquals("romance", genre.slug)
            assertEquals("/fiction/romance", genre.path)
            assertEquals(200, genre.bookCount)
        }

    @Test
    fun `observeGenresForBook returns multiple genres for a book`() =
        runTest {
            // Given
            val genres =
                listOf(
                    createTestGenreEntity(id = "g1", name = "Fiction", slug = "fiction", path = "/fiction"),
                    createTestGenreEntity(id = "g2", name = "Fantasy", slug = "fantasy", path = "/fiction/fantasy"),
                )
            val dao = createMockDao()
            every { dao.observeGenresForBook(BookId("book-1")) } returns flowOf(genres)
            val repository = createRepository(dao)

            // When
            val result = repository.observeGenresForBook("book-1").first()

            // Then
            assertEquals(2, result.size)
            assertEquals("Fiction", result[0].name)
            assertEquals("Fantasy", result[1].name)
        }

    @Test
    fun `observeGenresForBook converts bookId string to BookId value class`() =
        runTest {
            // Given
            val dao = createMockDao()
            every { dao.observeGenresForBook(BookId("my-book-id")) } returns flowOf(emptyList())
            val repository = createRepository(dao)

            // When
            repository.observeGenresForBook("my-book-id").first()

            // Then
            verify { dao.observeGenresForBook(BookId("my-book-id")) }
        }

    // ========== getGenresForBook Tests ==========

    @Test
    fun `getGenresForBook returns empty list when book has no genres`() =
        runTest {
            // Given
            val dao = createMockDao()
            everySuspend { dao.getGenresForBook(BookId("book-1")) } returns emptyList()
            val repository = createRepository(dao)

            // When
            val result = repository.getGenresForBook("book-1")

            // Then
            assertTrue(result.isEmpty())
        }

    @Test
    fun `getGenresForBook transforms entities to domain models`() =
        runTest {
            // Given
            val genreEntity =
                createTestGenreEntity(
                    id = "genre-6",
                    name = "Historical Fiction",
                    slug = "historical-fiction",
                    path = "/fiction/historical-fiction",
                    bookCount = 60,
                )
            val dao = createMockDao()
            everySuspend { dao.getGenresForBook(BookId("book-1")) } returns listOf(genreEntity)
            val repository = createRepository(dao)

            // When
            val result = repository.getGenresForBook("book-1")

            // Then
            assertEquals(1, result.size)
            val genre = result.first()
            assertEquals("genre-6", genre.id)
            assertEquals("Historical Fiction", genre.name)
            assertEquals("historical-fiction", genre.slug)
            assertEquals("/fiction/historical-fiction", genre.path)
            assertEquals(60, genre.bookCount)
        }

    @Test
    fun `getGenresForBook returns multiple genres for a book`() =
        runTest {
            // Given
            val genres =
                listOf(
                    createTestGenreEntity(id = "g1", name = "Non-Fiction", slug = "non-fiction", path = "/non-fiction"),
                    createTestGenreEntity(id = "g2", name = "Science", slug = "science", path = "/non-fiction/science"),
                    createTestGenreEntity(id = "g3", name = "Physics", slug = "physics", path = "/non-fiction/science/physics"),
                )
            val dao = createMockDao()
            everySuspend { dao.getGenresForBook(BookId("book-1")) } returns genres
            val repository = createRepository(dao)

            // When
            val result = repository.getGenresForBook("book-1")

            // Then
            assertEquals(3, result.size)
            assertEquals("Non-Fiction", result[0].name)
            assertEquals("Science", result[1].name)
            assertEquals("Physics", result[2].name)
        }

    @Test
    fun `getGenresForBook converts bookId string to BookId value class`() =
        runTest {
            // Given
            val dao = createMockDao()
            everySuspend { dao.getGenresForBook(BookId("another-book-id")) } returns emptyList()
            val repository = createRepository(dao)

            // When
            repository.getGenresForBook("another-book-id")

            // Then
            verifySuspend { dao.getGenresForBook(BookId("another-book-id")) }
        }

    // ========== getBookIdsForGenre Tests ==========

    @Test
    fun `getBookIdsForGenre returns empty list when no books have genre`() =
        runTest {
            // Given
            val dao = createMockDao()
            everySuspend { dao.getBookIdsForGenre("genre-1") } returns emptyList()
            val repository = createRepository(dao)

            // When
            val result = repository.getBookIdsForGenre("genre-1")

            // Then
            assertTrue(result.isEmpty())
        }

    @Test
    fun `getBookIdsForGenre returns book ids as strings`() =
        runTest {
            // Given
            val bookIds =
                listOf(
                    BookId("book-1"),
                    BookId("book-2"),
                    BookId("book-3"),
                )
            val dao = createMockDao()
            everySuspend { dao.getBookIdsForGenre("genre-1") } returns bookIds
            val repository = createRepository(dao)

            // When
            val result = repository.getBookIdsForGenre("genre-1")

            // Then
            assertEquals(3, result.size)
            assertEquals("book-1", result[0])
            assertEquals("book-2", result[1])
            assertEquals("book-3", result[2])
        }

    @Test
    fun `getBookIdsForGenre extracts value from BookId`() =
        runTest {
            // Given - BookId is a value class wrapping a String
            val bookIds = listOf(BookId("test-book-id"))
            val dao = createMockDao()
            everySuspend { dao.getBookIdsForGenre("genre-1") } returns bookIds
            val repository = createRepository(dao)

            // When
            val result = repository.getBookIdsForGenre("genre-1")

            // Then
            assertEquals(1, result.size)
            assertEquals("test-book-id", result[0])
        }

    @Test
    fun `getBookIdsForGenre passes correct genreId to dao`() =
        runTest {
            // Given
            val dao = createMockDao()
            everySuspend { dao.getBookIdsForGenre("target-genre-id") } returns emptyList()
            val repository = createRepository(dao)

            // When
            repository.getBookIdsForGenre("target-genre-id")

            // Then
            verifySuspend { dao.getBookIdsForGenre("target-genre-id") }
        }

    // ========== Entity to Domain Conversion Tests ==========

    @Test
    fun `toDomain converts all entity fields correctly`() =
        runTest {
            // Given
            val genreEntity =
                createTestGenreEntity(
                    id = "complete-genre",
                    name = "Urban Fantasy",
                    slug = "urban-fantasy",
                    path = "/fiction/fantasy/urban-fantasy",
                    bookCount = 150,
                )
            val dao = createMockDao()
            everySuspend { dao.getById("complete-genre") } returns genreEntity
            val repository = createRepository(dao)

            // When
            val genre = repository.getById("complete-genre")

            // Then - verify all fields are mapped
            assertEquals("complete-genre", genre?.id)
            assertEquals("Urban Fantasy", genre?.name)
            assertEquals("urban-fantasy", genre?.slug)
            assertEquals("/fiction/fantasy/urban-fantasy", genre?.path)
            assertEquals(150, genre?.bookCount)
        }

    @Test
    fun `toDomain handles root level genre with simple path`() =
        runTest {
            // Given
            val genreEntity =
                createTestGenreEntity(
                    id = "root-genre",
                    name = "Fiction",
                    slug = "fiction",
                    path = "/fiction",
                    bookCount = 1000,
                )
            val dao = createMockDao()
            everySuspend { dao.getById("root-genre") } returns genreEntity
            val repository = createRepository(dao)

            // When
            val genre = repository.getById("root-genre")

            // Then
            assertEquals("Fiction", genre?.name)
            assertEquals("/fiction", genre?.path)
            // Domain model parentPath should be null for root level
            assertNull(genre?.parentPath)
        }

    @Test
    fun `toDomain handles deeply nested genre path`() =
        runTest {
            // Given
            val genreEntity =
                createTestGenreEntity(
                    id = "nested-genre",
                    name = "Hard Science Fiction",
                    slug = "hard-science-fiction",
                    path = "/fiction/science-fiction/hard-science-fiction",
                    bookCount = 30,
                )
            val dao = createMockDao()
            everySuspend { dao.getById("nested-genre") } returns genreEntity
            val repository = createRepository(dao)

            // When
            val genre = repository.getById("nested-genre")

            // Then
            assertEquals("Hard Science Fiction", genre?.name)
            assertEquals("/fiction/science-fiction/hard-science-fiction", genre?.path)
            // Domain model parentPath should show hierarchy
            assertEquals("Fiction > Science-fiction", genre?.parentPath)
        }

    @Test
    fun `toDomain handles zero book count`() =
        runTest {
            // Given
            val genreEntity =
                createTestGenreEntity(
                    id = "empty-genre",
                    name = "New Genre",
                    slug = "new-genre",
                    path = "/new-genre",
                    bookCount = 0,
                )
            val dao = createMockDao()
            everySuspend { dao.getById("empty-genre") } returns genreEntity
            val repository = createRepository(dao)

            // When
            val genre = repository.getById("empty-genre")

            // Then
            assertEquals(0, genre?.bookCount)
        }

    @Test
    fun `toDomain preserves default values for entity optional fields`() =
        runTest {
            // Given - entity with default values for optional fields
            val genreEntity =
                GenreEntity(
                    id = "minimal-genre",
                    name = "Minimal",
                    slug = "minimal",
                    path = "/minimal",
                    // Using defaults for bookCount, parentId, depth, sortOrder
                )
            val dao = createMockDao()
            everySuspend { dao.getById("minimal-genre") } returns genreEntity
            val repository = createRepository(dao)

            // When
            val genre = repository.getById("minimal-genre")

            // Then
            assertEquals("minimal-genre", genre?.id)
            assertEquals("Minimal", genre?.name)
            assertEquals("minimal", genre?.slug)
            assertEquals("/minimal", genre?.path)
            assertEquals(0, genre?.bookCount) // Default value
        }
}
