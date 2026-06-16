package com.calypsan.listenup.client.domain.usecase.book

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.TestData
import com.calypsan.listenup.client.checkIs
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.domain.model.Genre
import com.calypsan.listenup.client.domain.model.Tag
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.GenreRepository
import com.calypsan.listenup.client.domain.repository.MoodRepository
import com.calypsan.listenup.client.domain.repository.TagRepository
import com.calypsan.listenup.client.presentation.bookedit.ContributorRole
import dev.mokkery.answering.returns
import dev.mokkery.answering.throws
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import kotlinx.coroutines.flow.flowOf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

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
class LoadBookForEditUseCaseTest :
    FunSpec({

        // ========== Test Fixtures ==========

        class TestFixture {
            val bookRepository: BookRepository = mock()
            val genreRepository: GenreRepository = mock()
            val tagRepository: TagRepository = mock()
            val moodRepository: MoodRepository = mock()

            fun build(): LoadBookForEditUseCase =
                LoadBookForEditUseCase(
                    bookRepository = bookRepository,
                    genreRepository = genreRepository,
                    tagRepository = tagRepository,
                    moodRepository = moodRepository,
                )
        }

        fun createFixture(): TestFixture {
            val fixture = TestFixture()

            // Default stubs for successful operations
            everySuspend { fixture.genreRepository.getAll() } returns emptyList()
            everySuspend { fixture.genreRepository.getGenresForBook(any()) } returns emptyList()
            every { fixture.tagRepository.observeAllTags() } returns flowOf(emptyList())
            every { fixture.tagRepository.observeTagsForBook(any()) } returns flowOf(emptyList())
            every { fixture.moodRepository.observeAllMoods() } returns flowOf(emptyList())
            every { fixture.moodRepository.observeMoodsForBook(any()) } returns flowOf(emptyList())

            return fixture
        }

        // ========== Book Not Found Tests ==========

        test("returns not found error when book does not exist") {
            runTest {
                // Given
                val fixture = createFixture()
                everySuspend { fixture.bookRepository.getBookDetail("nonexistent") } returns null
                val useCase = fixture.build()

                // When
                val result = useCase("nonexistent")

                // Then
                val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                failure.error.shouldBeInstanceOf<com.calypsan.listenup.api.error.ValidationError>()
                (failure.message.contains("not found", ignoreCase = true)) shouldBe true
            }
        }

        // ========== Metadata Transformation Tests ==========

        test("transforms book metadata correctly") {
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
                val success = result.shouldBeInstanceOf<AppResult.Success<*>>()
                val editData = success.data as com.calypsan.listenup.client.domain.model.BookEditData

                editData.bookId shouldBe "book-1"
                editData.metadata.title shouldBe "The Great Gatsby"
                editData.metadata.subtitle shouldBe "A Novel"
                editData.metadata.description shouldBe "Jazz Age story"
                editData.metadata.publishYear shouldBe "1925"
                editData.metadata.publisher shouldBe "Scribner"
                editData.metadata.language shouldBe "en"
                editData.metadata.isbn shouldBe "978-0743273565"
                editData.metadata.asin shouldBe "B000FC0PDA"
                editData.metadata.abridged shouldBe false
            }
        }

        test("handles null optional fields with empty strings") {
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
                val success = result.shouldBeInstanceOf<AppResult.Success<*>>()
                val editData = success.data as com.calypsan.listenup.client.domain.model.BookEditData

                editData.metadata.subtitle shouldBe ""
                editData.metadata.description shouldBe ""
                editData.metadata.publishYear shouldBe ""
                editData.metadata.publisher shouldBe ""
                editData.metadata.language shouldBe null
                editData.metadata.isbn shouldBe ""
                editData.metadata.asin shouldBe ""
            }
        }

        // ========== Contributor Transformation Tests ==========

        test("transforms contributors to editable format with roles") {
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
                val success = result.shouldBeInstanceOf<AppResult.Success<*>>()
                val editData = success.data as com.calypsan.listenup.client.domain.model.BookEditData

                editData.contributors.size shouldBe 2

                val editableAuthor = editData.contributors.find { it.id == "c1" }
                editableAuthor.shouldNotBeNull()
                editableAuthor.name shouldBe "Jane Austen"
                (editableAuthor.roles.contains(ContributorRole.AUTHOR)) shouldBe true

                val editableNarrator = editData.contributors.find { it.id == "c2" }
                editableNarrator.shouldNotBeNull()
                editableNarrator.name shouldBe "Rosamund Pike"
                (editableNarrator.roles.contains(ContributorRole.NARRATOR)) shouldBe true
            }
        }

        test("handles contributors with multiple roles") {
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
                val success = result.shouldBeInstanceOf<AppResult.Success<*>>()
                val editData = success.data as com.calypsan.listenup.client.domain.model.BookEditData

                editData.contributors.size shouldBe 1
                val contributor = editData.contributors.first()
                contributor.roles.size shouldBe 2
                (contributor.roles.contains(ContributorRole.AUTHOR)) shouldBe true
                (contributor.roles.contains(ContributorRole.NARRATOR)) shouldBe true
            }
        }

        // ========== Series Transformation Tests ==========

        test("transforms series to editable format") {
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
                val success = result.shouldBeInstanceOf<AppResult.Success<*>>()
                val editData = success.data as com.calypsan.listenup.client.domain.model.BookEditData

                editData.series.size shouldBe 1
                val series = editData.series.first()
                series.id shouldBe "lotr-series"
                series.name shouldBe "The Lord of the Rings"
                series.sequence shouldBe "1"
            }
        }

        test("handles book with no series") {
            runTest {
                // Given
                val book = TestData.bookDetail(id = "book-1")
                val fixture = createFixture()
                everySuspend { fixture.bookRepository.getBookDetail("book-1") } returns book
                val useCase = fixture.build()

                // When
                val result = useCase("book-1")

                // Then
                val success = result.shouldBeInstanceOf<AppResult.Success<*>>()
                val editData = success.data as com.calypsan.listenup.client.domain.model.BookEditData

                editData.series.isEmpty() shouldBe true
            }
        }

        // ========== Genre Loading Tests ==========

        test("loads all genres for picker") {
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
                val success = result.shouldBeInstanceOf<AppResult.Success<*>>()
                val editData = success.data as com.calypsan.listenup.client.domain.model.BookEditData

                editData.allGenres.size shouldBe 3
                (editData.allGenres.any { it.id == "g1" && it.name == "Fiction" }) shouldBe true
                (editData.allGenres.any { it.id == "g2" && it.name == "Mystery" }) shouldBe true
                (editData.allGenres.any { it.id == "g3" && it.name == "Romance" }) shouldBe true
            }
        }

        test("loads genres assigned to book") {
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
                val success = result.shouldBeInstanceOf<AppResult.Success<*>>()
                val editData = success.data as com.calypsan.listenup.client.domain.model.BookEditData

                editData.genres.size shouldBe 1
                editData.genres.first().id shouldBe "g1"
                editData.genres.first().name shouldBe "Fiction"
            }
        }

        test("returns empty genres when genre loading fails") {
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
                val success = result.shouldBeInstanceOf<AppResult.Success<*>>()
                val editData = success.data as com.calypsan.listenup.client.domain.model.BookEditData

                editData.allGenres.isEmpty() shouldBe true
                editData.genres.isEmpty() shouldBe true
            }
        }

        // ========== Tag Loading Tests ==========

        test("loads all tags for picker") {
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
                every { fixture.tagRepository.observeAllTags() } returns flowOf(allTags)
                val useCase = fixture.build()

                // When
                val result = useCase("book-1")

                // Then
                val success = result.shouldBeInstanceOf<AppResult.Success<*>>()
                val editData = success.data as com.calypsan.listenup.client.domain.model.BookEditData

                editData.allTags.size shouldBe 3
                (editData.allTags.any { it.id == "t1" && it.slug == "favorites" }) shouldBe true
                (editData.allTags.any { it.id == "t2" && it.slug == "to-read" }) shouldBe true
                (editData.allTags.any { it.id == "t3" && it.slug == "completed" }) shouldBe true
            }
        }

        test("loads tags assigned to book") {
            runTest {
                // Given
                val bookTags =
                    listOf(
                        TestData.tag(id = "t1", slug = "favorites"),
                    )
                val book = TestData.bookDetail(id = "book-1")
                val fixture = createFixture()
                everySuspend { fixture.bookRepository.getBookDetail("book-1") } returns book
                every { fixture.tagRepository.observeTagsForBook("book-1") } returns flowOf(bookTags)
                val useCase = fixture.build()

                // When
                val result = useCase("book-1")

                // Then
                val success = result.shouldBeInstanceOf<AppResult.Success<*>>()
                val editData = success.data as com.calypsan.listenup.client.domain.model.BookEditData

                editData.tags.size shouldBe 1
                editData.tags.first().id shouldBe "t1"
                editData.tags.first().slug shouldBe "favorites"
            }
        }

        test("returns empty tags when tag loading fails") {
            runTest {
                // Given
                val book = TestData.bookDetail(id = "book-1")
                val fixture = createFixture()
                everySuspend { fixture.bookRepository.getBookDetail("book-1") } returns book
                every { fixture.tagRepository.observeAllTags() } throws Exception("Network error")
                every { fixture.tagRepository.observeTagsForBook(any()) } throws Exception("Network error")
                val useCase = fixture.build()

                // When
                val result = useCase("book-1")

                // Then - should still succeed, just with empty tags
                val success = result.shouldBeInstanceOf<AppResult.Success<*>>()
                val editData = success.data as com.calypsan.listenup.client.domain.model.BookEditData

                editData.allTags.isEmpty() shouldBe true
                editData.tags.isEmpty() shouldBe true
            }
        }

        // ========== Mood Loading Tests ==========

        test("loads all moods for picker") {
            runTest {
                // Given
                val allMoods =
                    listOf(
                        TestData.mood(id = "m1", slug = "feel-good"),
                        TestData.mood(id = "m2", slug = "tense"),
                        TestData.mood(id = "m3", slug = "scary"),
                    )
                val book = TestData.bookDetail(id = "book-1")
                val fixture = createFixture()
                everySuspend { fixture.bookRepository.getBookDetail("book-1") } returns book
                every { fixture.moodRepository.observeAllMoods() } returns flowOf(allMoods)
                val useCase = fixture.build()

                // When
                val result = useCase("book-1")

                // Then
                val success = result.shouldBeInstanceOf<AppResult.Success<*>>()
                val editData = success.data as com.calypsan.listenup.client.domain.model.BookEditData

                editData.allMoods.size shouldBe 3
                (editData.allMoods.any { it.id == "m1" && it.slug == "feel-good" }) shouldBe true
                (editData.allMoods.any { it.id == "m2" && it.slug == "tense" }) shouldBe true
                (editData.allMoods.any { it.id == "m3" && it.slug == "scary" }) shouldBe true
            }
        }

        test("loads moods assigned to book") {
            runTest {
                // Given
                val bookMoods =
                    listOf(
                        TestData.mood(id = "m1", slug = "feel-good"),
                    )
                val book = TestData.bookDetail(id = "book-1")
                val fixture = createFixture()
                everySuspend { fixture.bookRepository.getBookDetail("book-1") } returns book
                every { fixture.moodRepository.observeMoodsForBook("book-1") } returns flowOf(bookMoods)
                val useCase = fixture.build()

                // When
                val result = useCase("book-1")

                // Then
                val success = result.shouldBeInstanceOf<AppResult.Success<*>>()
                val editData = success.data as com.calypsan.listenup.client.domain.model.BookEditData

                editData.moods.size shouldBe 1
                editData.moods.first().id shouldBe "m1"
                editData.moods.first().slug shouldBe "feel-good"
            }
        }

        test("returns empty moods when mood loading fails") {
            runTest {
                // Given
                val book = TestData.bookDetail(id = "book-1")
                val fixture = createFixture()
                everySuspend { fixture.bookRepository.getBookDetail("book-1") } returns book
                every { fixture.moodRepository.observeAllMoods() } throws Exception("Network error")
                every { fixture.moodRepository.observeMoodsForBook(any()) } throws Exception("Network error")
                val useCase = fixture.build()

                // When
                val result = useCase("book-1")

                // Then - should still succeed, just with empty moods
                val success = result.shouldBeInstanceOf<AppResult.Success<*>>()
                val editData = success.data as com.calypsan.listenup.client.domain.model.BookEditData

                editData.allMoods.isEmpty() shouldBe true
                editData.moods.isEmpty() shouldBe true
            }
        }

        // ========== Cover Path Tests ==========

        test("includes cover path from book") {
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
                val success = result.shouldBeInstanceOf<AppResult.Success<*>>()
                val editData = success.data as com.calypsan.listenup.client.domain.model.BookEditData

                editData.coverPath shouldBe "/covers/great-gatsby.jpg"
            }
        }

        test("handles null cover path") {
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
                val success = result.shouldBeInstanceOf<AppResult.Success<*>>()
                val editData = success.data as com.calypsan.listenup.client.domain.model.BookEditData

                editData.coverPath shouldBe null
            }
        }

        // ========== Full Integration Test ==========

        test("loads complete book edit data") {
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
                val allMoods =
                    listOf(
                        TestData.mood(id = "m1", slug = "feel-good"),
                        TestData.mood(id = "m2", slug = "tense"),
                    )
                val bookMoods = listOf(allMoods[1])

                val fixture = createFixture()
                everySuspend { fixture.bookRepository.getBookDetail("stormlight-1") } returns book
                everySuspend { fixture.genreRepository.getAll() } returns allGenres
                everySuspend { fixture.genreRepository.getGenresForBook("stormlight-1") } returns bookGenres
                every { fixture.tagRepository.observeAllTags() } returns flowOf(allTags)
                every { fixture.tagRepository.observeTagsForBook("stormlight-1") } returns flowOf(bookTags)
                every { fixture.moodRepository.observeAllMoods() } returns flowOf(allMoods)
                every { fixture.moodRepository.observeMoodsForBook("stormlight-1") } returns flowOf(bookMoods)
                val useCase = fixture.build()

                // When
                val result = useCase("stormlight-1")

                // Then
                val success = result.shouldBeInstanceOf<AppResult.Success<*>>()
                val editData = success.data as com.calypsan.listenup.client.domain.model.BookEditData

                // Verify all data is present
                editData.bookId shouldBe "stormlight-1"
                editData.metadata.title shouldBe "The Way of Kings"
                editData.metadata.subtitle shouldBe "Book One of The Stormlight Archive"
                editData.contributors.size shouldBe 2
                editData.series.size shouldBe 1
                editData.series.first().name shouldBe "The Stormlight Archive"
                editData.allGenres.size shouldBe 2
                editData.genres.size shouldBe 1
                editData.genres.first().name shouldBe "Epic Fantasy"
                editData.allTags.size shouldBe 2
                editData.tags.size shouldBe 1
                editData.tags.first().slug shouldBe "favorites"
                editData.allMoods.size shouldBe 2
                editData.moods.size shouldBe 1
                editData.moods.first().slug shouldBe "tense"
                editData.coverPath shouldBe "/covers/way-of-kings.jpg"
            }
        }
    })
