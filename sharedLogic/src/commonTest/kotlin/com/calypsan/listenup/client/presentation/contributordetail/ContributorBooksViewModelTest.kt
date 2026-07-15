package com.calypsan.listenup.client.presentation.contributordetail

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.BookSeries
import com.calypsan.listenup.client.domain.model.Contributor
import com.calypsan.listenup.client.domain.model.ContributorRole
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.client.domain.repository.BookWithContributorRole
import com.calypsan.listenup.client.domain.repository.ContributorRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
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
import kotlin.time.Clock

/**
 * Tests for ContributorBooksViewModel.
 *
 * The VM uses `.stateIn(WhileSubscribed)`, so tests must keep a background
 * collector alive via keepStateHot before asserting on `state.value`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContributorBooksViewModelTest :
    FunSpec({

        val testDispatcher = StandardTestDispatcher()

        // ========== Test Fixtures ==========

        class TestFixture {
            val contributorRepository: ContributorRepository = mock()
            val playbackPositionRepository: PlaybackPositionRepository = mock()

            val contributorFlow = MutableStateFlow<Contributor?>(null)
            val booksFlow = MutableStateFlow<List<BookWithContributorRole>>(emptyList())

            fun build(): ContributorBooksViewModel =
                ContributorBooksViewModel(
                    contributorRepository = contributorRepository,
                    playbackPositionRepository = playbackPositionRepository,
                )
        }

        fun createFixture(): TestFixture {
            val fixture = TestFixture()

            every { fixture.contributorRepository.observeById(any()) } returns fixture.contributorFlow
            every { fixture.contributorRepository.observeBooksForContributorRole(any(), any()) } returns fixture.booksFlow
            everySuspend { fixture.playbackPositionRepository.get(any<BookId>()) } returns AppResult.Success(null)

            return fixture
        }

        fun createContributor(
            id: String = "contributor-1",
            name: String = "Stephen King",
        ): Contributor =
            Contributor(
                id =
                    com.calypsan.listenup.core
                        .ContributorId(id),
                name = name,
                description = null,
                imagePath = null,
                website = null,
                birthDate = null,
                deathDate = null,
                aliases = emptyList(),
            )

        fun createBook(
            id: String = "book-1",
            title: String = "Test Book",
            duration: Long = 3_600_000L,
            coverPath: String? = null,
            series: List<BookSeries> = emptyList(),
        ): BookListItem =
            BookListItem(
                id = BookId(id),
                libraryId = LibraryId("test-library"),
                folderId = FolderId("test-folder"),
                title = title,
                coverPath = coverPath,
                duration = duration,
                authors = emptyList(),
                narrators = emptyList(),
                addedAt = Timestamp(1704067200000L),
                updatedAt = Timestamp(1704067200000L),
                series = series,
            )

        fun createBookWithContributorRole(
            book: BookListItem,
            creditedAs: String? = null,
        ): BookWithContributorRole =
            BookWithContributorRole(
                book = book,
                creditedAs = creditedAs,
            )

        fun createPlaybackPosition(
            bookId: String,
            positionMs: Long,
        ): PlaybackPosition =
            PlaybackPosition(
                bookId = bookId,
                positionMs = positionMs,
                playbackSpeed = 1.0f,
                hasCustomSpeed = false,
                updatedAtMs = Clock.System.now().toEpochMilliseconds(),
                syncedAtMs = null,
                lastPlayedAtMs = null,
            )

        beforeTest {
            Dispatchers.setMain(testDispatcher)
        }

        afterTest {
            Dispatchers.resetMain()
        }

        // ========== Initial State ==========

        test("initial state is Idle pre-loadBooks") {
            runTest {
                val fixture = createFixture()
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.state.collect { } }
                advanceUntilIdle()

                viewModel.state.value shouldBe ContributorBooksUiState.Idle
            }
        }

        // ========== Load Books ==========

        test("loadBooks populates contributor name") {
            runTest {
                val fixture = createFixture()
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.state.collect { } }

                viewModel.loadBooks("contributor-1", ContributorRole.AUTHOR.apiValue)
                fixture.contributorFlow.value = createContributor(name = "Stephen King")
                advanceUntilIdle()

                val state = viewModel.state.value as ContributorBooksUiState.Ready
                state.contributorName shouldBe "Stephen King"
            }
        }

        test("loadBooks sets role display name") {
            runTest {
                val fixture = createFixture()
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.state.collect { } }

                viewModel.loadBooks("contributor-1", ContributorRole.AUTHOR.apiValue)
                fixture.contributorFlow.value = createContributor()
                advanceUntilIdle()

                val state = viewModel.state.value as ContributorBooksUiState.Ready
                state.roleDisplayName shouldBe "Written By"
            }
        }

        test("loadBooks groups books by series") {
            runTest {
                val fixture = createFixture()
                val book1 =
                    createBook(
                        id = "book-1",
                        title = "The Dark Tower",
                        series = listOf(BookSeries(seriesId = "dark-tower-series", seriesName = "Dark Tower", sequence = "1")),
                    )
                val book2 =
                    createBook(
                        id = "book-2",
                        title = "The Drawing of the Three",
                        series = listOf(BookSeries(seriesId = "dark-tower-series", seriesName = "Dark Tower", sequence = "2")),
                    )
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.state.collect { } }

                viewModel.loadBooks("contributor-1", ContributorRole.AUTHOR.apiValue)
                fixture.contributorFlow.value = createContributor()
                fixture.booksFlow.value =
                    listOf(
                        createBookWithContributorRole(book1),
                        createBookWithContributorRole(book2),
                    )
                advanceUntilIdle()

                val state = viewModel.state.value as ContributorBooksUiState.Ready
                state.seriesGroups.size shouldBe 1
                state.seriesGroups[0].seriesName shouldBe "Dark Tower"
                state.seriesGroups[0].books.size shouldBe 2
                (state.standaloneBooks.isEmpty()) shouldBe true
            }
        }

        test("loadBooks separates standalone books from series books") {
            runTest {
                val fixture = createFixture()
                val seriesBook =
                    createBook(
                        id = "book-1",
                        title = "Series Book",
                        series = listOf(BookSeries(seriesId = "a-series", seriesName = "A Series", sequence = null)),
                    )
                val standaloneBook = createBook(id = "book-2", title = "Standalone Book")
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.state.collect { } }

                viewModel.loadBooks("contributor-1", ContributorRole.AUTHOR.apiValue)
                fixture.contributorFlow.value = createContributor()
                fixture.booksFlow.value =
                    listOf(
                        createBookWithContributorRole(seriesBook),
                        createBookWithContributorRole(standaloneBook),
                    )
                advanceUntilIdle()

                val state = viewModel.state.value as ContributorBooksUiState.Ready
                state.seriesGroups.size shouldBe 1
                state.standaloneBooks.size shouldBe 1
                state.standaloneBooks[0].title shouldBe "Standalone Book"
            }
        }

        test("loadBooks sorts series books by sequence") {
            runTest {
                val fixture = createFixture()
                val book1 =
                    createBook(
                        id = "book-1",
                        title = "Book One",
                        series = listOf(BookSeries(seriesId = "test-series", seriesName = "Series", sequence = "2")),
                    )
                val book2 =
                    createBook(
                        id = "book-2",
                        title = "Book Two",
                        series = listOf(BookSeries(seriesId = "test-series", seriesName = "Series", sequence = "1")),
                    )
                val book3 =
                    createBook(
                        id = "book-3",
                        title = "Book Three",
                        series = listOf(BookSeries(seriesId = "test-series", seriesName = "Series", sequence = "1.5")),
                    )
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.state.collect { } }

                viewModel.loadBooks("contributor-1", ContributorRole.AUTHOR.apiValue)
                fixture.contributorFlow.value = createContributor()
                fixture.booksFlow.value =
                    listOf(
                        createBookWithContributorRole(book1),
                        createBookWithContributorRole(book2),
                        createBookWithContributorRole(book3),
                    )
                advanceUntilIdle()

                val state = viewModel.state.value as ContributorBooksUiState.Ready
                val seriesBooks = state.seriesGroups[0].books
                seriesBooks[0].title shouldBe "Book Two"
                seriesBooks[1].title shouldBe "Book Three"
                seriesBooks[2].title shouldBe "Book One"
            }
        }

        test("loadBooks sorts standalone books alphabetically") {
            runTest {
                val fixture = createFixture()
                val book1 = createBook(id = "book-1", title = "Zebra")
                val book2 = createBook(id = "book-2", title = "Alpha")
                val book3 = createBook(id = "book-3", title = "Beta")
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.state.collect { } }

                viewModel.loadBooks("contributor-1", ContributorRole.AUTHOR.apiValue)
                fixture.contributorFlow.value = createContributor()
                fixture.booksFlow.value =
                    listOf(
                        createBookWithContributorRole(book1),
                        createBookWithContributorRole(book2),
                        createBookWithContributorRole(book3),
                    )
                advanceUntilIdle()

                val state = viewModel.state.value as ContributorBooksUiState.Ready
                state.standaloneBooks[0].title shouldBe "Alpha"
                state.standaloneBooks[1].title shouldBe "Beta"
                state.standaloneBooks[2].title shouldBe "Zebra"
            }
        }

        test("loadBooks sorts series groups alphabetically by series name") {
            runTest {
                val fixture = createFixture()
                val book1 =
                    createBook(
                        id = "book-1",
                        title = "Book",
                        series = listOf(BookSeries(seriesId = "zebra-series", seriesName = "Zebra Series", sequence = null)),
                    )
                val book2 =
                    createBook(
                        id = "book-2",
                        title = "Book",
                        series = listOf(BookSeries(seriesId = "alpha-series", seriesName = "Alpha Series", sequence = null)),
                    )
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.state.collect { } }

                viewModel.loadBooks("contributor-1", ContributorRole.AUTHOR.apiValue)
                fixture.contributorFlow.value = createContributor()
                fixture.booksFlow.value =
                    listOf(
                        createBookWithContributorRole(book1),
                        createBookWithContributorRole(book2),
                    )
                advanceUntilIdle()

                val state = viewModel.state.value as ContributorBooksUiState.Ready
                state.seriesGroups.size shouldBe 2
                state.seriesGroups[0].seriesName shouldBe "Alpha Series"
                state.seriesGroups[1].seriesName shouldBe "Zebra Series"
            }
        }

        // ========== Book Progress ==========

        test("loadBooks calculates book progress") {
            runTest {
                val fixture = createFixture()
                val book = createBook(id = "book-1", duration = 10_000L)
                everySuspend { fixture.playbackPositionRepository.get(BookId("book-1")) } returns
                    AppResult.Success(createPlaybackPosition("book-1", 5_000L))
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.state.collect { } }

                viewModel.loadBooks("contributor-1", ContributorRole.AUTHOR.apiValue)
                fixture.contributorFlow.value = createContributor()
                fixture.booksFlow.value = listOf(createBookWithContributorRole(book))
                advanceUntilIdle()

                val state = viewModel.state.value as ContributorBooksUiState.Ready
                state.bookProgress[BookId("book-1")] shouldBe 0.5f
            }
        }

        test("loadBooks excludes completed books from progress") {
            runTest {
                val fixture = createFixture()
                val book = createBook(id = "book-1", duration = 10_000L)
                everySuspend { fixture.playbackPositionRepository.get(BookId("book-1")) } returns
                    AppResult.Success(createPlaybackPosition("book-1", 9_999L))
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.state.collect { } }

                viewModel.loadBooks("contributor-1", ContributorRole.AUTHOR.apiValue)
                fixture.contributorFlow.value = createContributor()
                fixture.booksFlow.value = listOf(createBookWithContributorRole(book))
                advanceUntilIdle()

                val state = viewModel.state.value as ContributorBooksUiState.Ready
                (state.bookProgress.containsKey(BookId("book-1"))) shouldBe false
            }
        }

        // ========== Derived Properties ==========

        test("totalBooks returns sum of series and standalone books") {
            runTest {
                val fixture = createFixture()
                val seriesBook1 =
                    createBook(
                        id = "book-1",
                        title = "Series 1",
                        series = listOf(BookSeries(seriesId = "s-series", seriesName = "S", sequence = null)),
                    )
                val seriesBook2 =
                    createBook(
                        id = "book-2",
                        title = "Series 2",
                        series = listOf(BookSeries(seriesId = "s-series", seriesName = "S", sequence = null)),
                    )
                val standalone = createBook(id = "book-3", title = "Standalone")
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.state.collect { } }

                viewModel.loadBooks("contributor-1", ContributorRole.AUTHOR.apiValue)
                fixture.contributorFlow.value = createContributor()
                fixture.booksFlow.value =
                    listOf(
                        createBookWithContributorRole(seriesBook1),
                        createBookWithContributorRole(seriesBook2),
                        createBookWithContributorRole(standalone),
                    )
                advanceUntilIdle()

                val state = viewModel.state.value as ContributorBooksUiState.Ready
                state.totalBooks shouldBe 3
            }
        }

        test("hasStandaloneBooks is true when standalone books exist") {
            runTest {
                val fixture = createFixture()
                val book = createBook(id = "book-1")
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.state.collect { } }

                viewModel.loadBooks("contributor-1", ContributorRole.AUTHOR.apiValue)
                fixture.contributorFlow.value = createContributor()
                fixture.booksFlow.value = listOf(createBookWithContributorRole(book))
                advanceUntilIdle()

                val state = viewModel.state.value as ContributorBooksUiState.Ready
                (state.hasStandaloneBooks) shouldBe true
            }
        }

        test("hasStandaloneBooks is false when no standalone books") {
            runTest {
                val fixture = createFixture()
                val book =
                    createBook(
                        id = "book-1",
                        series = listOf(BookSeries(seriesId = "test-series", seriesName = "Series", sequence = null)),
                    )
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.state.collect { } }

                viewModel.loadBooks("contributor-1", ContributorRole.AUTHOR.apiValue)
                fixture.contributorFlow.value = createContributor()
                fixture.booksFlow.value = listOf(createBookWithContributorRole(book))
                advanceUntilIdle()

                val state = viewModel.state.value as ContributorBooksUiState.Ready
                (state.hasStandaloneBooks) shouldBe false
            }
        }

        // ========== Cover Path ==========

        test("loadBooks passes through coverPath from domain model") {
            runTest {
                val fixture = createFixture()
                val book = createBook(id = "book-1", coverPath = "/path/to/cover.jpg")
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.state.collect { } }

                viewModel.loadBooks("contributor-1", ContributorRole.AUTHOR.apiValue)
                fixture.contributorFlow.value = createContributor()
                fixture.booksFlow.value = listOf(createBookWithContributorRole(book))
                advanceUntilIdle()

                val state = viewModel.state.value as ContributorBooksUiState.Ready
                state.standaloneBooks[0].coverPath shouldBe "/path/to/cover.jpg"
            }
        }

        // ========== Empty State ==========

        test("loadBooks handles empty book list") {
            runTest {
                val fixture = createFixture()
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.state.collect { } }

                viewModel.loadBooks("contributor-1", ContributorRole.AUTHOR.apiValue)
                fixture.contributorFlow.value = createContributor()
                fixture.booksFlow.value = emptyList()
                advanceUntilIdle()

                val state = viewModel.state.value as ContributorBooksUiState.Ready
                (state.seriesGroups.isEmpty()) shouldBe true
                (state.standaloneBooks.isEmpty()) shouldBe true
                state.totalBooks shouldBe 0
            }
        }
    })
