package com.calypsan.listenup.client.domain.usecase.book

import com.calypsan.listenup.client.TestData
import com.calypsan.listenup.client.checkIs
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.domain.model.Genre
import com.calypsan.listenup.client.domain.model.Tag
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.GenreRepository
import com.calypsan.listenup.client.domain.repository.TagRepository
import com.calypsan.listenup.client.presentation.bookedit.ContributorRole
import dev.mokkery.answering.returns
import dev.mokkery.answering.throws
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for LoadBookForEditUseCase.
 *
 * Tests cover:
 * - Book not found handling
 * - Successful book loading with metadata transformation
 * - Contributor transformation to editable format
 * - Series transformation to editable format
 * - Genre loading (all and for book)
 * - Tag loading (all and for book)
 * - Error handling for genre/tag loading failures
 */
class LoadBookForEditUseCaseTest {
    // ========== Test Fixtures ==========

    private class TestFixture {
        val bookRepository: BookRepository = mock()
        val genreRepository: GenreRepository = mock()
        val tagRepository: TagRepository = mock()

        fun build(): LoadBookForEditUseCase =
            LoadBookForEditUseCase(
                bookRepository = bookRepository,
                genreRepository = genreRepository,
                tagRepository = tagRepository,
            )
    }

    private fun createFixture(): TestFixture {
        val fixture = TestFixture()

        // Default stubs for successful operations
        everySuspend { fixture.genreRepository.getAll() } returns emptyList()
        everySuspend { fixture.genreRepository.getGenresForBook(any()) } returns emptyList()
        everySuspend { fixture.tagRepository.getAll() } returns emptyList()
        everySuspend { fixture.tagRepository.getTagsForBook(any()) } returns emptyList()

        return fixture
    }

    // ========== Book Not Found Tests ==========

    @Test
    fun `returns not found error when book does not exist`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.bookRepository.getBookDetail("nonexistent") } returns null
            val useCase = fixture.build()

            // When
            val result = useCase("nonexistent")

            // Then
            val failure = assertIs<Failure>(result)
            assertIs<com.calypsan.listenup.api.error.ValidationError>(failure.error)
            assertTrue(failure.message.contains("not found", ignoreCase = true))
        }

    // ========== Metadata Transformation Tests ==========

    @Test
    fun `transforms book metadata correctly`() =
        runTest {
            // Given
            val book =
                TestData.bookDetail(
                    id = "book-1",
                    title = "The Great Gatsby",
                    subtitle = "A Novel",
                    description = "Jazz Age story",
                    publishYear = 1925,
                    publisher = "Scribner",
                    language = "en",
                    isbn = "978-0743273565",
                    asin = "B000FC0PDA",
                    abridged = false,
                )
            val fixture = createFixture()
            everySuspend { fixture.bookRepository.getBookDetail("book-1") } returns book
            val useCase = fixture.build()

            // When
            val result = useCase("book-1")

            // Then
            val success = assertIs<Success<*>>(result)
            val editData = success.data as com.calypsan.listenup.client.domain.model.BookEditData

            assertEquals("book-1", editData.bookId)
            assertEquals("The Great Gatsby", editData.metadata.title)
            assertEquals("A Novel", editData.metadata.subtitle)
            assertEquals("Jazz Age story", editData.metadata.description)
            assertEquals("1925", editData.metadata.publishYear)
            assertEquals("Scribner", editData.metadata.publisher)
            assertEquals("en", editData.metadata.language)
            assertEquals("978-0743273565", editData.metadata.isbn)
            assertEquals("B000FC0PDA", editData.metadata.asin)
            assertEquals(false, editData.metadata.abridged)
        }

    @Test
    fun `handles null optional fields with empty strings`() =
        runTest {
            // Given
            val book =
                TestData.bookDetail(
                    id = "book-1",
                    title = "Minimal Book",
                    subtitle = null,
                    description = null,
                    publishYear = null,
                    publisher = null,
                    language = null,
                    isbn = null,
                    asin = null,
                )
            val fixture = createFixture()
            everySuspend { fixture.bookRepository.getBookDetail("book-1") } returns book
            val useCase = fixture.build()

            // When
            val result = useCase("book-1")

            // Then
            val success = assertIs<Success<*>>(result)
            val editData = success.data as com.calypsan.listenup.client.domain.model.BookEditData

            assertEquals("", editData.metadata.subtitle)
            assertEquals("", editData.metadata.description)
            assertEquals("", editData.metadata.publishYear)
            assertEquals("", editData.metadata.publisher)
            assertEquals(null, editData.metadata.language)
            assertEquals("", editData.metadata.isbn)
            assertEquals("", editData.metadata.asin)
        }

    // ========== Contributor Transformation Tests ==========

    @Test
    fun `transforms contributors to editable format with roles`() =
        runTest {
            // Given
            val author = TestData.contributor(id = "c1", name = "Jane Austen", roles = listOf("Author"))
            val narrator = TestData.contributor(id = "c2", name = "Rosamund Pike", roles = listOf("Narrator"))
            val book =
                TestData.bookDetail(
                    id = "book-1",
                    allContributors = listOf(author, narrator),
                )
            val fixture = createFixture()
            everySuspend { fixture.bookRepository.getBookDetail("book-1") } returns book
            val useCase = fixture.build()

            // When
            val result = useCase("book-1")

            // Then
            val success = assertIs<Success<*>>(result)
            val editData = success.data as com.calypsan.listenup.client.domain.model.BookEditData

            assertEquals(2, editData.contributors.size)

            val editableAuthor = editData.contributors.find { it.id == "c1" }
            assertNotNull(editableAuthor)
            assertEquals("Jane Austen", editableAuthor.name)
            assertTrue(editableAuthor.roles.contains(ContributorRole.AUTHOR))

            val editableNarrator = editData.contributors.find { it.id == "c2" }
            assertNotNull(editableNarrator)
            assertEquals("Rosamund Pike", editableNarrator.name)
            assertTrue(editableNarrator.roles.contains(ContributorRole.NARRATOR))
        }

    @Test
    fun `handles contributors with multiple roles`() =
        runTest {
            // Given
            val multiRoleContributor =
                TestData.contributor(
                    id = "c1",
                    name = "Neil Gaiman",
                    roles = listOf("Author", "Narrator"),
                )
            val book =
                TestData.bookDetail(
                    id = "book-1",
                    allContributors = listOf(multiRoleContributor),
                )
            val fixture = createFixture()
            everySuspend { fixture.bookRepository.getBookDetail("book-1") } returns book
            val useCase = fixture.build()

            // When
            val result = useCase("book-1")

            // Then
            val success = assertIs<Success<*>>(result)
            val editData = success.data as com.calypsan.listenup.client.domain.model.BookEditData

            assertEquals(1, editData.contributors.size)
            val contributor = editData.contributors.first()
            assertEquals(2, contributor.roles.size)
            assertTrue(contributor.roles.contains(ContributorRole.AUTHOR))
            assertTrue(contributor.roles.contains(ContributorRole.NARRATOR))
        }

    // ========== Series Transformation Tests ==========

    @Test
    fun `transforms series to editable format`() =
        runTest {
            // Given
            val book =
                TestData.bookInSeries(
                    id = "book-1",
                    title = "The Fellowship of the Ring",
                    seriesId = "lotr-series",
                    seriesName = "The Lord of the Rings",
                    seriesSequence = "1",
                )
            val fixture = createFixture()
            everySuspend { fixture.bookRepository.getBookDetail("book-1") } returns book
            val useCase = fixture.build()

            // When
            val result = useCase("book-1")

            // Then
            val success = assertIs<Success<*>>(result)
            val editData = success.data as com.calypsan.listenup.client.domain.model.BookEditData

            assertEquals(1, editData.series.size)
            val series = editData.series.first()
            assertEquals("lotr-series", series.id)
            assertEquals("The Lord of the Rings", series.name)
            assertEquals("1", series.sequence)
        }

    @Test
    fun `handles book with no series`() =
        runTest {
            // Given
            val book = TestData.bookDetail(id = "book-1")
            val fixture = createFixture()
            everySuspend { fixture.bookRepository.getBookDetail("book-1") } returns book
            val useCase = fixture.build()

            // When
            val result = useCase("book-1")

            // Then
            val success = assertIs<Success<*>>(result)
            val editData = success.data as com.calypsan.listenup.client.domain.model.BookEditData

            assertTrue(editData.series.isEmpty())
        }

    // ========== Genre Loading Tests ==========

    @Test
    fun `loads all genres for picker`() =
        runTest {
            // Given
            val allGenres =
                listOf(
                    TestData.genre(id = "g1", name = "Fiction", path = "/fiction"),
                    TestData.genre(id = "g2", name = "Mystery", path = "/mystery"),
                    TestData.genre(id = "g3", name = "Romance", path = "/romance"),
                )
            val book = TestData.bookDetail(id = "book-1")
            val fixture = createFixture()
            everySuspend { fixture.bookRepository.getBookDetail("book-1") } returns book
            everySuspend { fixture.genreRepository.getAll() } returns allGenres
            val useCase = fixture.build()

            // When
            val result = useCase("book-1")

            // Then
            val success = assertIs<Success<*>>(result)
            val editData = success.data as com.calypsan.listenup.client.domain.model.BookEditData

            assertEquals(3, editData.allGenres.size)
            assertTrue(editData.allGenres.any { it.id == "g1" && it.name == "Fiction" })
            assertTrue(editData.allGenres.any { it.id == "g2" && it.name == "Mystery" })
            assertTrue(editData.allGenres.any { it.id == "g3" && it.name == "Romance" })
        }

    @Test
    fun `loads genres assigned to book`() =
        runTest {
            // Given
            val bookGenres =
                listOf(
                    TestData.genre(id = "g1", name = "Fiction", path = "/fiction"),
                )
            val book = TestData.bookDetail(id = "book-1")
            val fixture = createFixture()
            everySuspend { fixture.bookRepository.getBookDetail("book-1") } returns book
            everySuspend { fixture.genreRepository.getGenresForBook("book-1") } returns bookGenres
            val useCase = fixture.build()

            // When
            val result = useCase("book-1")

            // Then
            val success = assertIs<Success<*>>(result)
            val editData = success.data as com.calypsan.listenup.client.domain.model.BookEditData

            assertEquals(1, editData.genres.size)
            assertEquals("g1", editData.genres.first().id)
            assertEquals("Fiction", editData.genres.first().name)
        }

    @Test
    fun `returns empty genres when genre loading fails`() =
        runTest {
            // Given
            val book = TestData.bookDetail(id = "book-1")
            val fixture = createFixture()
            everySuspend { fixture.bookRepository.getBookDetail("book-1") } returns book
            everySuspend { fixture.genreRepository.getAll() } throws Exception("Network error")
            everySuspend { fixture.genreRepository.getGenresForBook(any()) } throws Exception("Network error")
            val useCase = fixture.build()

            // When
            val result = useCase("book-1")

            // Then - should still succeed, just with empty genres
            val success = assertIs<Success<*>>(result)
            val editData = success.data as com.calypsan.listenup.client.domain.model.BookEditData

            assertTrue(editData.allGenres.isEmpty())
            assertTrue(editData.genres.isEmpty())
        }

    // ========== Tag Loading Tests ==========

    @Test
    fun `loads all tags for picker`() =
        runTest {
            // Given
            val allTags =
                listOf(
                    TestData.tag(id = "t1", slug = "favorites"),
                    TestData.tag(id = "t2", slug = "to-read"),
                    TestData.tag(id = "t3", slug = "completed"),
                )
            val book = TestData.bookDetail(id = "book-1")
            val fixture = createFixture()
            everySuspend { fixture.bookRepository.getBookDetail("book-1") } returns book
            everySuspend { fixture.tagRepository.getAll() } returns allTags
            val useCase = fixture.build()

            // When
            val result = useCase("book-1")

            // Then
            val success = assertIs<Success<*>>(result)
            val editData = success.data as com.calypsan.listenup.client.domain.model.BookEditData

            assertEquals(3, editData.allTags.size)
            assertTrue(editData.allTags.any { it.id == "t1" && it.slug == "favorites" })
            assertTrue(editData.allTags.any { it.id == "t2" && it.slug == "to-read" })
            assertTrue(editData.allTags.any { it.id == "t3" && it.slug == "completed" })
        }

    @Test
    fun `loads tags assigned to book`() =
        runTest {
            // Given
            val bookTags =
                listOf(
                    TestData.tag(id = "t1", slug = "favorites"),
                )
            val book = TestData.bookDetail(id = "book-1")
            val fixture = createFixture()
            everySuspend { fixture.bookRepository.getBookDetail("book-1") } returns book
            everySuspend { fixture.tagRepository.getTagsForBook("book-1") } returns bookTags
            val useCase = fixture.build()

            // When
            val result = useCase("book-1")

            // Then
            val success = assertIs<Success<*>>(result)
            val editData = success.data as com.calypsan.listenup.client.domain.model.BookEditData

            assertEquals(1, editData.tags.size)
            assertEquals("t1", editData.tags.first().id)
            assertEquals("favorites", editData.tags.first().slug)
        }

    @Test
    fun `returns empty tags when tag loading fails`() =
        runTest {
            // Given
            val book = TestData.bookDetail(id = "book-1")
            val fixture = createFixture()
            everySuspend { fixture.bookRepository.getBookDetail("book-1") } returns book
            everySuspend { fixture.tagRepository.getAll() } throws Exception("Network error")
            everySuspend { fixture.tagRepository.getTagsForBook(any()) } throws Exception("Network error")
            val useCase = fixture.build()

            // When
            val result = useCase("book-1")

            // Then - should still succeed, just with empty tags
            val success = assertIs<Success<*>>(result)
            val editData = success.data as com.calypsan.listenup.client.domain.model.BookEditData

            assertTrue(editData.allTags.isEmpty())
            assertTrue(editData.tags.isEmpty())
        }

    // ========== Cover Path Tests ==========

    @Test
    fun `includes cover path from book`() =
        runTest {
            // Given
            val book =
                TestData.bookDetail(
                    id = "book-1",
                    coverPath = "/covers/great-gatsby.jpg",
                )
            val fixture = createFixture()
            everySuspend { fixture.bookRepository.getBookDetail("book-1") } returns book
            val useCase = fixture.build()

            // When
            val result = useCase("book-1")

            // Then
            val success = assertIs<Success<*>>(result)
            val editData = success.data as com.calypsan.listenup.client.domain.model.BookEditData

            assertEquals("/covers/great-gatsby.jpg", editData.coverPath)
        }

    @Test
    fun `handles null cover path`() =
        runTest {
            // Given
            val book =
                TestData.bookDetail(
                    id = "book-1",
                    coverPath = null,
                )
            val fixture = createFixture()
            everySuspend { fixture.bookRepository.getBookDetail("book-1") } returns book
            val useCase = fixture.build()

            // When
            val result = useCase("book-1")

            // Then
            val success = assertIs<Success<*>>(result)
            val editData = success.data as com.calypsan.listenup.client.domain.model.BookEditData

            assertEquals(null, editData.coverPath)
        }

    // ========== Full Integration Test ==========

    @Test
    fun `loads complete book edit data`() =
        runTest {
            // Given - a fully populated book with all data
            val author = TestData.contributor(id = "c1", name = "Brandon Sanderson", roles = listOf("Author"))
            val narrator = TestData.contributor(id = "c2", name = "Michael Kramer", roles = listOf("Narrator"))
            val book =
                TestData.bookDetail(
                    id = "stormlight-1",
                    title = "The Way of Kings",
                    subtitle = "Book One of The Stormlight Archive",
                    description = "Epic fantasy at its finest",
                    publishYear = 2010,
                    publisher = "Tor Books",
                    language = "en",
                    isbn = "978-0765326355",
                    asin = "B003P2WO5E",
                    abridged = false,
                    seriesId = "stormlight",
                    seriesName = "The Stormlight Archive",
                    seriesSequence = "1",
                    allContributors = listOf(author, narrator),
                    coverPath = "/covers/way-of-kings.jpg",
                )
            val allGenres =
                listOf(
                    TestData.genre(id = "g1", name = "Fantasy", path = "/fantasy"),
                    TestData.genre(id = "g2", name = "Epic Fantasy", path = "/fantasy/epic"),
                )
            val bookGenres = listOf(allGenres[1])
            val allTags =
                listOf(
                    TestData.tag(id = "t1", slug = "favorites"),
                    TestData.tag(id = "t2", slug = "to-read"),
                )
            val bookTags = listOf(allTags[0])

            val fixture = createFixture()
            everySuspend { fixture.bookRepository.getBookDetail("stormlight-1") } returns book
            everySuspend { fixture.genreRepository.getAll() } returns allGenres
            everySuspend { fixture.genreRepository.getGenresForBook("stormlight-1") } returns bookGenres
            everySuspend { fixture.tagRepository.getAll() } returns allTags
            everySuspend { fixture.tagRepository.getTagsForBook("stormlight-1") } returns bookTags
            val useCase = fixture.build()

            // When
            val result = useCase("stormlight-1")

            // Then
            val success = assertIs<Success<*>>(result)
            val editData = success.data as com.calypsan.listenup.client.domain.model.BookEditData

            // Verify all data is present
            assertEquals("stormlight-1", editData.bookId)
            assertEquals("The Way of Kings", editData.metadata.title)
            assertEquals("Book One of The Stormlight Archive", editData.metadata.subtitle)
            assertEquals(2, editData.contributors.size)
            assertEquals(1, editData.series.size)
            assertEquals("The Stormlight Archive", editData.series.first().name)
            assertEquals(2, editData.allGenres.size)
            assertEquals(1, editData.genres.size)
            assertEquals("Epic Fantasy", editData.genres.first().name)
            assertEquals(2, editData.allTags.size)
            assertEquals(1, editData.tags.size)
            assertEquals("favorites", editData.tags.first().slug)
            assertEquals("/covers/way-of-kings.jpg", editData.coverPath)
        }
}
