package com.calypsan.listenup.client.presentation.seriesdetail

import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.BookSeries
import com.calypsan.listenup.client.domain.model.Entity
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.client.domain.model.Series
import com.calypsan.listenup.client.domain.model.SeriesWithBooks
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.domain.repository.SeriesRepository
import com.calypsan.listenup.client.test.fake.FakeEntityEditRepository
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class SeriesDetailViewModelTest :
    FunSpec({

        val testDispatcher = StandardTestDispatcher()

        class TestFixture {
            val seriesRepository: SeriesRepository = mock()
            val imageRepository: ImageRepository = mock()
            val playbackPositionRepository: PlaybackPositionRepository = mock()
            val entityEditRepository = FakeEntityEditRepository()
            val seriesFlow = MutableStateFlow<SeriesWithBooks?>(null)
            val positionsFlow = MutableStateFlow<Map<BookId, PlaybackPosition>>(emptyMap())

            fun build(): SeriesDetailViewModel =
                SeriesDetailViewModel(
                    seriesRepository = seriesRepository,
                    imageRepository = imageRepository,
                    playbackPositionRepository = playbackPositionRepository,
                    entityEditRepository = entityEditRepository,
                )
        }

        fun createFixture(): TestFixture {
            val fixture = TestFixture()
            every { fixture.seriesRepository.observeSeriesWithBooks(any()) } returns fixture.seriesFlow
            every { fixture.imageRepository.seriesCoverExists(any()) } returns false
            every { fixture.playbackPositionRepository.observeAll() } returns fixture.positionsFlow
            return fixture
        }

        fun createPosition(
            bookId: String,
            positionMs: Long = 0L,
            isFinished: Boolean = false,
        ): PlaybackPosition =
            PlaybackPosition(
                bookId = bookId,
                positionMs = positionMs,
                playbackSpeed = 1.0f,
                hasCustomSpeed = false,
                updatedAtMs = 0L,
                syncedAtMs = null,
                lastPlayedAtMs = null,
                isFinished = isFinished,
            )

        fun createSeries(
            id: String = "series-1",
            name: String = "Test Series",
            description: String? = "A great series",
        ): Series =
            Series(
                id =
                    com.calypsan.listenup.core
                        .SeriesId(id),
                name = name,
                description = description,
                createdAt = Timestamp(1704067200000L),
            )

        fun createBook(
            id: String = "book-1",
            title: String = "Test Book",
            seriesSequence: String? = "1",
            seriesId: String = "series-1",
            seriesName: String = "Test Series",
        ): BookListItem =
            BookListItem(
                id = BookId(id),
                libraryId = LibraryId("test-library"),
                folderId = FolderId("test-folder"),
                title = title,
                subtitle = null,
                authors = listOf(BookContributor(id = "author-1", name = "Author", roles = listOf("Author"))),
                narrators = emptyList(),
                duration = 3_600_000L,
                coverPath = null,
                addedAt = Timestamp(1704067200000L),
                updatedAt = Timestamp(1704067200000L),
                series = listOf(BookSeries(seriesId = seriesId, seriesName = seriesName, sequence = seriesSequence)),
            )

        fun createSeriesWithBooks(
            series: Series,
            books: List<BookListItem>,
            bookSequences: Map<String, String?> = emptyMap(),
        ): SeriesWithBooks =
            SeriesWithBooks(
                series = series,
                books = books,
                bookSequences = bookSequences,
            )

        beforeTest {
            Dispatchers.setMain(testDispatcher)
        }

        afterTest {
            Dispatchers.resetMain()
        }

        // ========== Initial State ==========

        test("initial state is Idle pre-loadSeries") {
            runTest {
                val fixture = createFixture()
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.state.collect { } }
                advanceUntilIdle()

                viewModel.state.value shouldBe SeriesDetailUiState.Idle
            }
        }

        // ========== Load Series ==========

        test("loadSeries success populates series data") {
            runTest {
                val fixture = createFixture()
                val series = createSeries(name = "Epic Fantasy Series", description = "An epic adventure")
                val book = createBook(id = "book-1", title = "Book One")
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.state.collect { } }

                viewModel.loadSeries("series-1")
                fixture.seriesFlow.value =
                    createSeriesWithBooks(
                        series = series,
                        books = listOf(book),
                        bookSequences = mapOf("book-1" to "1"),
                    )
                advanceUntilIdle()

                val state = viewModel.state.value.shouldBeInstanceOf<SeriesDetailUiState.Ready>()
                state.seriesName shouldBe "Epic Fantasy Series"
                state.seriesDescription shouldBe "An epic adventure"
                state.books.size shouldBe 1
                state.books[0].title shouldBe "Book One"
            }
        }

        test("loadSeries not found sets error state") {
            runTest {
                val fixture = createFixture()
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.state.collect { } }

                viewModel.loadSeries("nonexistent")
                fixture.seriesFlow.value = null
                advanceUntilIdle()

                val state = viewModel.state.value.shouldBeInstanceOf<SeriesDetailUiState.Error>()
                state.message shouldBe "Series not found"
            }
        }

        test("loadSeries sorts books by sequence number") {
            runTest {
                val fixture = createFixture()
                val series = createSeries()
                val book1 = createBook(id = "book-1", title = "Book One", seriesSequence = "1")
                val book2 = createBook(id = "book-2", title = "Book Two", seriesSequence = "2")
                val book3 = createBook(id = "book-3", title = "Book Three", seriesSequence = "1.5")
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.state.collect { } }

                viewModel.loadSeries("series-1")
                fixture.seriesFlow.value =
                    createSeriesWithBooks(
                        series = series,
                        books = listOf(book2, book3, book1),
                        bookSequences = mapOf("book-1" to "1", "book-2" to "2", "book-3" to "1.5"),
                    )
                advanceUntilIdle()

                val state = viewModel.state.value.shouldBeInstanceOf<SeriesDetailUiState.Ready>()
                state.books.size shouldBe 3
                state.books[0].title shouldBe "Book One"
                state.books[1].title shouldBe "Book Three"
                state.books[2].title shouldBe "Book Two"
            }
        }

        test("loadSeries handles books with null sequence") {
            runTest {
                val fixture = createFixture()
                val series = createSeries()
                val book1 = createBook(id = "book-1", title = "Numbered Book", seriesSequence = "1")
                val book2 = createBook(id = "book-2", title = "Unnumbered Book", seriesSequence = null)
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.state.collect { } }

                viewModel.loadSeries("series-1")
                fixture.seriesFlow.value =
                    createSeriesWithBooks(
                        series = series,
                        books = listOf(book2, book1),
                        bookSequences = mapOf("book-1" to "1", "book-2" to null),
                    )
                advanceUntilIdle()

                val state = viewModel.state.value.shouldBeInstanceOf<SeriesDetailUiState.Ready>()
                state.books.size shouldBe 2
                state.books[0].title shouldBe "Numbered Book"
                state.books[1].title shouldBe "Unnumbered Book"
            }
        }

        test("loadSeries handles null series description") {
            runTest {
                val fixture = createFixture()
                val series = createSeries(description = null)
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.state.collect { } }

                viewModel.loadSeries("series-1")
                fixture.seriesFlow.value = createSeriesWithBooks(series = series, books = emptyList())
                advanceUntilIdle()

                val state = viewModel.state.value.shouldBeInstanceOf<SeriesDetailUiState.Ready>()
                state.seriesDescription shouldBe null
            }
        }

        test("loadSeries handles empty books list") {
            runTest {
                val fixture = createFixture()
                val series = createSeries(name = "Empty Series")
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.state.collect { } }

                viewModel.loadSeries("series-1")
                fixture.seriesFlow.value = createSeriesWithBooks(series = series, books = emptyList())
                advanceUntilIdle()

                val state = viewModel.state.value.shouldBeInstanceOf<SeriesDetailUiState.Ready>()
                state.seriesName shouldBe "Empty Series"
                (state.books.isEmpty()) shouldBe true
            }
        }

        test("loadSeries updates when flow emits new value") {
            runTest {
                val fixture = createFixture()
                val series1 = createSeries(name = "Original Name")
                val series2 = createSeries(name = "Updated Name")
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.state.collect { } }

                viewModel.loadSeries("series-1")
                fixture.seriesFlow.value = createSeriesWithBooks(series = series1, books = emptyList())
                advanceUntilIdle()
                val first = viewModel.state.value.shouldBeInstanceOf<SeriesDetailUiState.Ready>()
                first.seriesName shouldBe "Original Name"

                fixture.seriesFlow.value = createSeriesWithBooks(series = series2, books = emptyList())
                advanceUntilIdle()

                val second = viewModel.state.value.shouldBeInstanceOf<SeriesDetailUiState.Ready>()
                second.seriesName shouldBe "Updated Name"
            }
        }

        // ========== G2 — resolveCoverPath priority tests ==========

        test("resolveCoverPath uses series.coverPath when no local file and no book covers") {
            // No local disk file, series has coverPath → should surface series.coverPath.
            runTest {
                val fixture = createFixture()
                every { fixture.imageRepository.seriesCoverExists(any()) } returns false
                val series =
                    Series(
                        id =
                            com.calypsan.listenup.core
                                .SeriesId("series-1"),
                        name = "Stormlight",
                        description = null,
                        createdAt = Timestamp(0L),
                        coverPath = ".listenup-meta/series/stormlight.jpg",
                        coverBlurHash = null,
                        asin = null,
                    )
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.state.collect { } }

                viewModel.loadSeries("series-1")
                fixture.seriesFlow.value = createSeriesWithBooks(series = series, books = emptyList())
                advanceUntilIdle()

                val state = viewModel.state.value.shouldBeInstanceOf<SeriesDetailUiState.Ready>()
                state.coverPath shouldBe ".listenup-meta/series/stormlight.jpg"
            }
        }

        test("resolveCoverPath prefers local disk over series.coverPath") {
            // Local file exists → use local path, not series.coverPath.
            runTest {
                val fixture = createFixture()
                every { fixture.imageRepository.seriesCoverExists(any()) } returns true
                every { fixture.imageRepository.getSeriesCoverPath(any()) } returns "/local/series-1.jpg"
                val series =
                    Series(
                        id =
                            com.calypsan.listenup.core
                                .SeriesId("series-1"),
                        name = "Mistborn",
                        description = null,
                        createdAt = Timestamp(0L),
                        coverPath = ".listenup-meta/series/mistborn.jpg",
                        coverBlurHash = null,
                        asin = null,
                    )
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.state.collect { } }

                viewModel.loadSeries("series-1")
                fixture.seriesFlow.value = createSeriesWithBooks(series = series, books = emptyList())
                advanceUntilIdle()

                val state = viewModel.state.value.shouldBeInstanceOf<SeriesDetailUiState.Ready>()
                state.coverPath shouldBe "/local/series-1.jpg"
            }
        }

        test("resolveCoverPath falls back to first book cover when series has no coverPath") {
            // No local file, series.coverPath is null → first book's coverPath.
            runTest {
                val fixture = createFixture()
                every { fixture.imageRepository.seriesCoverExists(any()) } returns false
                val series =
                    Series(
                        id =
                            com.calypsan.listenup.core
                                .SeriesId("series-1"),
                        name = "Cosmere",
                        description = null,
                        createdAt = Timestamp(0L),
                        coverPath = null,
                        coverBlurHash = null,
                        asin = null,
                    )
                val book = createBook(id = "book-1", title = "Way of Kings").copy(coverPath = "/books/wok.jpg")
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.state.collect { } }

                viewModel.loadSeries("series-1")
                fixture.seriesFlow.value =
                    createSeriesWithBooks(
                        series = series,
                        books = listOf(book),
                        bookSequences = mapOf("book-1" to "1"),
                    )
                advanceUntilIdle()

                val state = viewModel.state.value.shouldBeInstanceOf<SeriesDetailUiState.Ready>()
                state.coverPath shouldBe "/books/wok.jpg"
            }
        }

        // ========== Progress, finished count, resume target, author ==========

        test("seriesAuthors aggregates the unique authors across every book, deduped by id in first-appearance order") {
            runTest {
                val fixture = createFixture()
                val series = createSeries()
                // Wheel of Time shape: Jordan writes the early books, Sanderson joins later; the
                // co-authored book lists both. The aggregate must keep first-appearance order and
                // drop the duplicate Jordan entry — not collapse to just the first book's author.
                val jordan = BookContributor(id = "a-jordan", name = "Robert Jordan", roles = listOf("Author"))
                val sanderson = BookContributor(id = "a-sanderson", name = "Brandon Sanderson", roles = listOf("Author"))
                val book1 = createBook(id = "book-1", seriesSequence = "1").copy(authors = listOf(jordan))
                val book2 = createBook(id = "book-2", seriesSequence = "2").copy(authors = listOf(jordan))
                val book3 =
                    createBook(id = "book-3", seriesSequence = "3").copy(authors = listOf(jordan, sanderson))
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.state.collect { } }

                viewModel.loadSeries("series-1")
                fixture.seriesFlow.value =
                    createSeriesWithBooks(
                        series = series,
                        books = listOf(book1, book2, book3),
                        bookSequences = mapOf("book-1" to "1", "book-2" to "2", "book-3" to "3"),
                    )
                advanceUntilIdle()

                val state = viewModel.state.value.shouldBeInstanceOf<SeriesDetailUiState.Ready>()
                state.seriesAuthors shouldBe listOf(jordan, sanderson)
            }
        }

        test("finished books are flagged and counted; in-progress book exposes its fraction") {
            runTest {
                val fixture = createFixture()
                val series = createSeries()
                val book1 = createBook(id = "book-1", title = "One", seriesSequence = "1")
                val book2 = createBook(id = "book-2", title = "Two", seriesSequence = "2")
                val book3 = createBook(id = "book-3", title = "Three", seriesSequence = "3")
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.state.collect { } }

                viewModel.loadSeries("series-1")
                // book duration is 3_600_000ms; 1_800_000ms = 50% in progress.
                fixture.positionsFlow.value =
                    mapOf(
                        BookId("book-1") to createPosition("book-1", isFinished = true),
                        BookId("book-2") to createPosition("book-2", positionMs = 1_800_000L),
                    )
                fixture.seriesFlow.value =
                    createSeriesWithBooks(
                        series = series,
                        books = listOf(book1, book2, book3),
                        bookSequences = mapOf("book-1" to "1", "book-2" to "2", "book-3" to "3"),
                    )
                advanceUntilIdle()

                val state = viewModel.state.value.shouldBeInstanceOf<SeriesDetailUiState.Ready>()
                state.finishedBookIds shouldBe setOf(BookId("book-1"))
                state.finishedCount shouldBe 1
                state.bookProgress[BookId("book-2")]!! shouldBe (0.5f plusOrMinus 0.001f)
                state.bookProgress.containsKey(BookId("book-1")) shouldBe false
                state.bookProgress.containsKey(BookId("book-3")) shouldBe false
            }
        }

        test("resumeTarget is the first in-progress book") {
            runTest {
                val fixture = createFixture()
                val series = createSeries()
                val book1 = createBook(id = "book-1", seriesSequence = "1")
                val book2 = createBook(id = "book-2", seriesSequence = "2")
                val book3 = createBook(id = "book-3", seriesSequence = "3")
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.state.collect { } }

                viewModel.loadSeries("series-1")
                fixture.positionsFlow.value =
                    mapOf(
                        BookId("book-1") to createPosition("book-1", isFinished = true),
                        BookId("book-2") to createPosition("book-2", positionMs = 900_000L),
                    )
                fixture.seriesFlow.value =
                    createSeriesWithBooks(
                        series = series,
                        books = listOf(book1, book2, book3),
                        bookSequences = mapOf("book-1" to "1", "book-2" to "2", "book-3" to "3"),
                    )
                advanceUntilIdle()

                val state = viewModel.state.value.shouldBeInstanceOf<SeriesDetailUiState.Ready>()
                state.resumeTarget shouldBe BookId("book-2")
            }
        }

        test("resumeTarget is the first unstarted book when none are in progress") {
            runTest {
                val fixture = createFixture()
                val series = createSeries()
                val book1 = createBook(id = "book-1", seriesSequence = "1")
                val book2 = createBook(id = "book-2", seriesSequence = "2")
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.state.collect { } }

                viewModel.loadSeries("series-1")
                fixture.positionsFlow.value =
                    mapOf(BookId("book-1") to createPosition("book-1", isFinished = true))
                fixture.seriesFlow.value =
                    createSeriesWithBooks(
                        series = series,
                        books = listOf(book1, book2),
                        bookSequences = mapOf("book-1" to "1", "book-2" to "2"),
                    )
                advanceUntilIdle()

                val state = viewModel.state.value.shouldBeInstanceOf<SeriesDetailUiState.Ready>()
                state.resumeTarget shouldBe BookId("book-2")
            }
        }

        test("resumeTarget is null when every book is finished") {
            runTest {
                val fixture = createFixture()
                val series = createSeries()
                val book1 = createBook(id = "book-1", seriesSequence = "1")
                val book2 = createBook(id = "book-2", seriesSequence = "2")
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.state.collect { } }

                viewModel.loadSeries("series-1")
                fixture.positionsFlow.value =
                    mapOf(
                        BookId("book-1") to createPosition("book-1", isFinished = true),
                        BookId("book-2") to createPosition("book-2", isFinished = true),
                    )
                fixture.seriesFlow.value =
                    createSeriesWithBooks(
                        series = series,
                        books = listOf(book1, book2),
                        bookSequences = mapOf("book-1" to "1", "book-2" to "2"),
                    )
                advanceUntilIdle()

                val state = viewModel.state.value.shouldBeInstanceOf<SeriesDetailUiState.Ready>()
                state.finishedCount shouldBe 2
                state.resumeTarget shouldBe null
            }
        }

        // ========== seriesNarrator ==========

        test("seriesNarrator surfaces the first book's first narrator") {
            runTest {
                val fixture = createFixture()
                val series = createSeries(id = "s1")
                val book =
                    createBook(id = "b1").copy(
                        narrators =
                            listOf(
                                BookContributor(id = "n1", name = "Robert Glenister", roles = listOf("Narrator")),
                            ),
                    )
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.state.collect { } }

                viewModel.loadSeries("s1")
                fixture.seriesFlow.value =
                    createSeriesWithBooks(series, listOf(book), mapOf("b1" to "1"))
                advanceUntilIdle()

                val state = viewModel.state.value.shouldBeInstanceOf<SeriesDetailUiState.Ready>()
                state.seriesNarrator shouldBe "Robert Glenister"
            }
        }

        // ========== entityCount (Story World entry card) ==========

        test("entityCount reflects entities seeded for the series; entities homed elsewhere don't count") {
            runTest {
                val fixture = createFixture()
                val series = createSeries(id = "series-1")
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.state.collect { } }

                viewModel.loadSeries("series-1")
                fixture.entityEditRepository.setEntities(
                    listOf(
                        Entity(id = "e1", kind = EntityKind.CHARACTER, name = "Kaladin", homeSeriesId = "series-1"),
                        Entity(id = "e2", kind = EntityKind.LOCATION, name = "Urithiru", homeSeriesId = "series-1"),
                        // Homed under a different series — must not count toward series-1's total.
                        Entity(id = "e3", kind = EntityKind.CHARACTER, name = "Vin", homeSeriesId = "series-2"),
                        // Homed under a standalone book, not any series — must not count either.
                        Entity(id = "e4", kind = EntityKind.ITEM, name = "Hemalurgic Spike", homeBookId = "book-1"),
                    ),
                )
                fixture.seriesFlow.value = createSeriesWithBooks(series = series, books = emptyList())
                advanceUntilIdle()

                val state = viewModel.state.value.shouldBeInstanceOf<SeriesDetailUiState.Ready>()
                state.entityCount shouldBe 2
            }
        }
    })
