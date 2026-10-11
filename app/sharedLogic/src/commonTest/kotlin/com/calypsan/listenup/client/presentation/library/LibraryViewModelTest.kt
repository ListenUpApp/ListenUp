package com.calypsan.listenup.client.presentation.library

import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.client.test.SimulatedFailure
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.BookSeries
import com.calypsan.listenup.client.domain.model.CombinedScore
import com.calypsan.listenup.client.domain.model.Contributor
import com.calypsan.listenup.client.domain.model.ContributorRole
import com.calypsan.listenup.client.domain.model.ContributorWithBookCount
import com.calypsan.listenup.client.domain.model.ListenerAverage
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.client.domain.model.Series
import com.calypsan.listenup.client.domain.model.SeriesWithBooks
import com.calypsan.listenup.client.domain.model.SyncState
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.BookRatingRepository
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.ContributorRepository
import com.calypsan.listenup.client.domain.repository.LibraryPreferences
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.domain.repository.SeriesRepository
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.domain.repository.SyncStatusRepository
import app.cash.turbine.turbineScope
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Tests for LibraryViewModel.
 *
 * Tests cover:
 * - Initial Loading state (before pipeline subscribes)
 * - Transition to Loaded after stateIn subscription
 * - Sort state initialization and persistence
 * - Sorting logic for books, series, and contributors
 * - Auto-sync behavior on screen visibility
 * - Event handling
 * - Per-upstream .catch gracefully degrades to empty defaults on transient failures
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest :
    FunSpec({

        val testDispatcher = StandardTestDispatcher()

        // ========== Test Data Factories ==========

        fun createTestBook(
            id: String = "book-1",
            title: String = "Test Book",
            authors: List<BookContributor> = listOf(BookContributor("author-1", "Test Author")),
            duration: Long = 3_600_000L, // 1 hour
            publishYear: Int? = 2023,
            seriesName: String? = null,
            seriesId: String? = null,
            seriesSequence: Double? = null,
            addedAt: Timestamp = Timestamp(1000L),
        ): BookListItem {
            val seriesList =
                if (seriesId != null && seriesName != null) {
                    listOf(BookSeries(seriesId = seriesId, seriesName = seriesName, sequence = seriesSequence))
                } else {
                    emptyList()
                }
            return BookListItem(
                id = BookId(id),
                libraryId = LibraryId("test-library"),
                folderId = FolderId("test-folder"),
                title = title,
                authors = authors,
                narrators = emptyList(),
                duration = duration,
                coverPath = null,
                addedAt = addedAt,
                updatedAt = addedAt,
                publishYear = publishYear,
                series = seriesList,
            )
        }

        fun createTestSeries(
            id: String = "series-1",
            name: String = "Test Series",
            createdAt: Timestamp = Timestamp(1000L),
        ): Series =
            Series(
                id =
                    com.calypsan.listenup.core
                        .SeriesId(id),
                name = name,
                description = null,
                createdAt = createdAt,
            )

        fun createTestContributor(
            id: String = "contrib-1",
            name: String = "Test Contributor",
            bookCount: Int = 5,
        ): ContributorWithBookCount =
            ContributorWithBookCount(
                contributor =
                    Contributor(
                        id =
                            com.calypsan.listenup.core
                                .ContributorId(id),
                        name = name,
                        description = null,
                        imagePath = null,
                    ),
                bookCount = bookCount,
            )

        fun createDummyBook(id: String): BookListItem {
            val now =
                Timestamp(
                    kotlin.time.Clock.System
                        .now()
                        .toEpochMilliseconds(),
                )
            return BookListItem(
                id = BookId(id),
                libraryId = LibraryId("test-library"),
                folderId = FolderId("test-folder"),
                title = "Book $id",
                coverPath = null,
                duration = 3_600_000L,
                authors = emptyList(),
                narrators = emptyList(),
                addedAt = now,
                updatedAt = now,
            )
        }

        // ========== Test Fixture Builder ==========

        class TestFixture {
            val bookRepository: BookRepository = mock()
            val seriesRepository: SeriesRepository = mock()
            val contributorRepository: ContributorRepository = mock()
            val syncRepository: SyncRepository = mock()
            val authSession: AuthSession = mock()
            val libraryPreferences: LibraryPreferences = mock()
            val syncStatusRepository: SyncStatusRepository = mock()
            val playbackPositionRepository: PlaybackPositionRepository = mock()
            val bookRatingRepository: BookRatingRepository = mock()

            val syncStateFlow = MutableStateFlow<SyncState>(SyncState.Idle)

            fun build(): LibraryViewModel =
                LibraryViewModel(
                    bookRepository = bookRepository,
                    seriesRepository = seriesRepository,
                    contributorRepository = contributorRepository,
                    playbackPositionRepository = playbackPositionRepository,
                    syncRepository = syncRepository,
                    authSession = authSession,
                    libraryPreferences = libraryPreferences,
                    syncStatusRepository = syncStatusRepository,
                    bookRatingRepository = bookRatingRepository,
                    backgroundDispatcher = testDispatcher,
                )
        }

        fun createFixture(): TestFixture {
            val fixture = TestFixture()

            // Default stubs for all dependencies
            every { fixture.bookRepository.observeBookListItems() } returns flowOf(emptyList<BookListItem>())
            every { fixture.seriesRepository.observeRootSeriesWithBooks() } returns flowOf(emptyList())
            every { fixture.contributorRepository.observeContributorsByRole(ContributorRole.AUTHOR.apiValue) } returns
                flowOf(emptyList())
            every { fixture.contributorRepository.observeContributorsByRole(ContributorRole.NARRATOR.apiValue) } returns
                flowOf(emptyList())
            every { fixture.syncRepository.syncState } returns fixture.syncStateFlow
            every { fixture.syncRepository.serverScanning } returns MutableStateFlow(false)
            every { fixture.syncRepository.scanProgress } returns MutableStateFlow(null)
            every { fixture.syncRepository.buildingInitialLibrary } returns MutableStateFlow(false)
            every { fixture.playbackPositionRepository.observeAll() } returns flowOf(emptyMap())
            every { fixture.bookRatingRepository.observeAverages() } returns flowOf(emptyMap())
            every { fixture.bookRatingRepository.observeCombinedScores() } returns flowOf(emptyMap())

            // Default library preferences stubs (no persisted state)
            everySuspend { fixture.libraryPreferences.getBooksSortState() } returns null
            everySuspend { fixture.libraryPreferences.getSeriesSortState() } returns null
            everySuspend { fixture.libraryPreferences.getAuthorsSortState() } returns null
            everySuspend { fixture.libraryPreferences.getNarratorsSortState() } returns null
            everySuspend { fixture.libraryPreferences.getIgnoreTitleArticles() } returns true
            everySuspend { fixture.libraryPreferences.getHideSingleBookSeries() } returns true

            return fixture
        }

        beforeTest {
            Dispatchers.setMain(testDispatcher)
        }

        afterTest {
            Dispatchers.resetMain()
        }

        // ========== Initial State Tests ==========

        test("initial state is Loading before pipeline subscribes") {
            runTest {
                // Given
                val fixture = createFixture()

                // When - no keepStateHot, so stateIn sits on its initialValue
                val viewModel = fixture.build()

                // Then
                viewModel.uiState.value.shouldBeInstanceOf<LibraryUiState.Loading>()
            }
        }

        test("pipeline transitions to Loaded after subscription") {
            runTest {
                // Given
                val fixture = createFixture()
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.uiState.collect { } }
                advanceUntilIdle()

                // Then
                viewModel.uiState.value.shouldBeInstanceOf<LibraryUiState.Loaded>()
            }
        }

        // ========== Sort State Initialization Tests ==========

        test("initial books sort state is title ascending") {
            runTest {
                // Given
                val fixture = createFixture()
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.uiState.collect { } }
                advanceUntilIdle()

                // Then
                val loaded = viewModel.uiState.value as LibraryUiState.Loaded
                loaded.booksSortState.category shouldBe SortCategory.TITLE
                loaded.booksSortState.direction shouldBe SortDirection.ASCENDING
            }
        }

        test("initial series sort state is name ascending") {
            runTest {
                // Given
                val fixture = createFixture()
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.uiState.collect { } }
                advanceUntilIdle()

                // Then
                val loaded = viewModel.uiState.value as LibraryUiState.Loaded
                loaded.seriesSortState.category shouldBe SortCategory.NAME
                loaded.seriesSortState.direction shouldBe SortDirection.ASCENDING
            }
        }

        test("initial authors sort state is name ascending") {
            runTest {
                // Given
                val fixture = createFixture()
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.uiState.collect { } }
                advanceUntilIdle()

                // Then
                val loaded = viewModel.uiState.value as LibraryUiState.Loaded
                loaded.authorsSortState.category shouldBe SortCategory.NAME
                loaded.authorsSortState.direction shouldBe SortDirection.ASCENDING
            }
        }

        test("loads persisted books sort state on init") {
            runTest {
                // Given
                val fixture = createFixture()
                everySuspend { fixture.libraryPreferences.getBooksSortState() } returns "duration:descending"

                // When
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.uiState.collect { } }
                advanceUntilIdle()

                // Then
                val loaded = viewModel.uiState.value as LibraryUiState.Loaded
                loaded.booksSortState.category shouldBe SortCategory.DURATION
                loaded.booksSortState.direction shouldBe SortDirection.DESCENDING
            }
        }

        test("loads persisted series sort state on init") {
            runTest {
                // Given
                val fixture = createFixture()
                everySuspend { fixture.libraryPreferences.getSeriesSortState() } returns "book_count:descending"

                // When
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.uiState.collect { } }
                advanceUntilIdle()

                // Then
                val loaded = viewModel.uiState.value as LibraryUiState.Loaded
                loaded.seriesSortState.category shouldBe SortCategory.BOOK_COUNT
                loaded.seriesSortState.direction shouldBe SortDirection.DESCENDING
            }
        }

        test("ignores invalid persisted sort state") {
            runTest {
                // Given
                val fixture = createFixture()
                everySuspend { fixture.libraryPreferences.getBooksSortState() } returns "invalid:garbage"

                // When
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.uiState.collect { } }
                advanceUntilIdle()

                // Then - falls back to default
                val loaded = viewModel.uiState.value as LibraryUiState.Loaded
                loaded.booksSortState.category shouldBe SortCategory.TITLE
                loaded.booksSortState.direction shouldBe SortDirection.ASCENDING
            }
        }

        // ========== Event Handling: Sort State Changes ==========

        test("BooksCategoryChanged updates books sort category") {
            runTest {
                // Given
                val fixture = createFixture()
                everySuspend { fixture.libraryPreferences.setBooksSortState(any()) } returns Unit
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.uiState.collect { } }
                advanceUntilIdle()

                // When
                viewModel.onEvent(LibraryUiEvent.BooksCategoryChanged(SortCategory.AUTHOR))
                advanceUntilIdle()

                // Then
                val loaded = viewModel.uiState.value as LibraryUiState.Loaded
                loaded.booksSortState.category shouldBe SortCategory.AUTHOR
            }
        }

        test("BooksCategoryChanged uses category default direction") {
            runTest {
                // Given
                val fixture = createFixture()
                everySuspend { fixture.libraryPreferences.setBooksSortState(any()) } returns Unit
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.uiState.collect { } }
                advanceUntilIdle()

                // When - DURATION defaults to DESCENDING
                viewModel.onEvent(LibraryUiEvent.BooksCategoryChanged(SortCategory.DURATION))
                advanceUntilIdle()

                // Then
                val loaded = viewModel.uiState.value as LibraryUiState.Loaded
                loaded.booksSortState.direction shouldBe SortDirection.DESCENDING
            }
        }

        test("BooksDirectionToggled toggles sort direction") {
            runTest {
                // Given
                val fixture = createFixture()
                everySuspend { fixture.libraryPreferences.setBooksSortState(any()) } returns Unit
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.uiState.collect { } }
                advanceUntilIdle()
                (viewModel.uiState.value as LibraryUiState.Loaded).booksSortState.direction shouldBe SortDirection.ASCENDING

                // When
                viewModel.onEvent(LibraryUiEvent.BooksDirectionToggled)
                advanceUntilIdle()

                // Then
                val loaded = viewModel.uiState.value as LibraryUiState.Loaded
                loaded.booksSortState.direction shouldBe SortDirection.DESCENDING
            }
        }

        test("sort state change persists to settings") {
            runTest {
                // Given
                val fixture = createFixture()
                everySuspend { fixture.libraryPreferences.setBooksSortState(any()) } returns Unit
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.uiState.collect { } }
                advanceUntilIdle()

                // When
                viewModel.onEvent(LibraryUiEvent.BooksCategoryChanged(SortCategory.YEAR))
                advanceUntilIdle()

                // Then
                verifySuspend { fixture.libraryPreferences.setBooksSortState("year:descending") }
            }
        }

        test("SeriesCategoryChanged updates series sort category") {
            runTest {
                // Given
                val fixture = createFixture()
                everySuspend { fixture.libraryPreferences.setSeriesSortState(any()) } returns Unit
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.uiState.collect { } }
                advanceUntilIdle()

                // When
                viewModel.onEvent(LibraryUiEvent.SeriesCategoryChanged(SortCategory.BOOK_COUNT))
                advanceUntilIdle()

                // Then
                val loaded = viewModel.uiState.value as LibraryUiState.Loaded
                loaded.seriesSortState.category shouldBe SortCategory.BOOK_COUNT
            }
        }

        test("AuthorsCategoryChanged updates authors sort category") {
            runTest {
                // Given
                val fixture = createFixture()
                everySuspend { fixture.libraryPreferences.setAuthorsSortState(any()) } returns Unit
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.uiState.collect { } }
                advanceUntilIdle()

                // When
                viewModel.onEvent(LibraryUiEvent.AuthorsCategoryChanged(SortCategory.BOOK_COUNT))
                advanceUntilIdle()

                // Then
                val loaded = viewModel.uiState.value as LibraryUiState.Loaded
                loaded.authorsSortState.category shouldBe SortCategory.BOOK_COUNT
            }
        }

        // ========== Toggle Ignore Title Articles ==========

        test("ToggleIgnoreTitleArticles toggles preference") {
            runTest {
                // Given
                val fixture = createFixture()
                everySuspend { fixture.libraryPreferences.setIgnoreTitleArticles(any()) } returns Unit
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.uiState.collect { } }
                advanceUntilIdle()
                (viewModel.uiState.value as LibraryUiState.Loaded).ignoreTitleArticles shouldBe true

                // When
                viewModel.onEvent(LibraryUiEvent.ToggleIgnoreTitleArticles)
                advanceUntilIdle()

                // Then
                val loaded = viewModel.uiState.value as LibraryUiState.Loaded
                loaded.ignoreTitleArticles shouldBe false
                verifySuspend { fixture.libraryPreferences.setIgnoreTitleArticles(false) }
            }
        }

        // ========== Books Sorting Tests ==========

        test("books sorted by title ascending") {
            runTest {
                // Given
                val books =
                    listOf(
                        createTestBook(id = "1", title = "Zebra"),
                        createTestBook(id = "2", title = "Apple"),
                        createTestBook(id = "3", title = "Mango"),
                    )
                val fixture = createFixture()
                every { fixture.bookRepository.observeBookListItems() } returns flowOf(books)
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.uiState.collect { } }
                advanceUntilIdle()

                // Then
                val loaded = viewModel.uiState.value as LibraryUiState.Loaded
                loaded.books.map { it.title } shouldBe listOf("Apple", "Mango", "Zebra")
            }
        }

        test("books sorted by title descending") {
            runTest {
                // Given
                val books =
                    listOf(
                        createTestBook(id = "1", title = "Apple"),
                        createTestBook(id = "2", title = "Zebra"),
                        createTestBook(id = "3", title = "Mango"),
                    )
                val fixture = createFixture()
                every { fixture.bookRepository.observeBookListItems() } returns flowOf(books)
                everySuspend { fixture.libraryPreferences.setBooksSortState(any()) } returns Unit
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.uiState.collect { } }
                advanceUntilIdle()

                // When
                viewModel.onEvent(LibraryUiEvent.BooksDirectionToggled)
                advanceUntilIdle()

                // Then
                val loaded = viewModel.uiState.value as LibraryUiState.Loaded
                loaded.books.map { it.title } shouldBe listOf("Zebra", "Mango", "Apple")
            }
        }

        test("books sorted by title ignores leading articles when enabled") {
            runTest {
                // Given - "The" should be ignored, so "The Zebra" sorts as "Zebra"
                val books =
                    listOf(
                        createTestBook(id = "1", title = "The Zebra"),
                        createTestBook(id = "2", title = "Apple"),
                        createTestBook(id = "3", title = "A Mango"),
                    )
                val fixture = createFixture()
                everySuspend { fixture.libraryPreferences.getIgnoreTitleArticles() } returns true
                every { fixture.bookRepository.observeBookListItems() } returns flowOf(books)
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.uiState.collect { } }
                advanceUntilIdle()

                // Then - Should sort as: Apple, A Mango (as "Mango"), The Zebra (as "Zebra")
                val loaded = viewModel.uiState.value as LibraryUiState.Loaded
                loaded.books.map { it.title } shouldBe listOf("Apple", "A Mango", "The Zebra")
            }
        }

        test("books sorted by author groups by author then title") {
            runTest {
                // Given
                val books =
                    listOf(
                        createTestBook(
                            id = "1",
                            title = "Zebra Book",
                            authors = listOf(BookContributor("a1", "Alice")),
                        ),
                        createTestBook(
                            id = "2",
                            title = "Apple Book",
                            authors = listOf(BookContributor("b1", "Bob")),
                        ),
                        createTestBook(
                            id = "3",
                            title = "Cherry Book",
                            authors = listOf(BookContributor("a1", "Alice")),
                        ),
                    )
                val fixture = createFixture()
                every { fixture.bookRepository.observeBookListItems() } returns flowOf(books)
                everySuspend { fixture.libraryPreferences.setBooksSortState(any()) } returns Unit
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.uiState.collect { } }
                advanceUntilIdle()

                // When
                viewModel.onEvent(LibraryUiEvent.BooksCategoryChanged(SortCategory.AUTHOR))
                advanceUntilIdle()

                // Then - Alice's books first (then by title), then Bob's
                val loaded = viewModel.uiState.value as LibraryUiState.Loaded
                loaded.books.map { it.title } shouldBe listOf("Cherry Book", "Zebra Book", "Apple Book")
            }
        }

        test("books sorted by duration ascending") {
            runTest {
                // Given
                val books =
                    listOf(
                        createTestBook(id = "1", title = "Long", duration = 10_000_000L),
                        createTestBook(id = "2", title = "Short", duration = 1_000_000L),
                        createTestBook(id = "3", title = "Medium", duration = 5_000_000L),
                    )
                val fixture = createFixture()
                every { fixture.bookRepository.observeBookListItems() } returns flowOf(books)
                everySuspend { fixture.libraryPreferences.setBooksSortState(any()) } returns Unit
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.uiState.collect { } }
                advanceUntilIdle()

                // When - Change to duration, then toggle to ascending
                viewModel.onEvent(LibraryUiEvent.BooksCategoryChanged(SortCategory.DURATION))
                advanceUntilIdle()
                viewModel.onEvent(LibraryUiEvent.BooksDirectionToggled) // DURATION defaults DESC, toggle to ASC
                advanceUntilIdle()

                // Then
                val loaded = viewModel.uiState.value as LibraryUiState.Loaded
                loaded.books.map { it.title } shouldBe listOf("Short", "Medium", "Long")
            }
        }

        test("books sorted by year descending handles null publish years") {
            runTest {
                // Given
                val books =
                    listOf(
                        createTestBook(id = "1", title = "Old", publishYear = 2020),
                        createTestBook(id = "2", title = "No Year", publishYear = null),
                        createTestBook(id = "3", title = "New", publishYear = 2024),
                    )
                val fixture = createFixture()
                every { fixture.bookRepository.observeBookListItems() } returns flowOf(books)
                everySuspend { fixture.libraryPreferences.setBooksSortState(any()) } returns Unit
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.uiState.collect { } }
                advanceUntilIdle()

                // When
                viewModel.onEvent(LibraryUiEvent.BooksCategoryChanged(SortCategory.YEAR))
                advanceUntilIdle()

                // Then - Year DESC: newest first, null years go to end (treated as 0)
                val loaded = viewModel.uiState.value as LibraryUiState.Loaded
                loaded.books.map { it.title } shouldBe listOf("New", "Old", "No Year")
            }
        }

        test("books sorted by added date descending") {
            runTest {
                // Given
                val books =
                    listOf(
                        createTestBook(id = "1", title = "First", addedAt = Timestamp(1000L)),
                        createTestBook(id = "2", title = "Last", addedAt = Timestamp(3000L)),
                        createTestBook(id = "3", title = "Middle", addedAt = Timestamp(2000L)),
                    )
                val fixture = createFixture()
                every { fixture.bookRepository.observeBookListItems() } returns flowOf(books)
                everySuspend { fixture.libraryPreferences.setBooksSortState(any()) } returns Unit
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.uiState.collect { } }
                advanceUntilIdle()

                // When
                viewModel.onEvent(LibraryUiEvent.BooksCategoryChanged(SortCategory.ADDED))
                advanceUntilIdle()

                // Then - Added DESC: most recent first
                val loaded = viewModel.uiState.value as LibraryUiState.Loaded
                loaded.books.map { it.title } shouldBe listOf("Last", "Middle", "First")
            }
        }

        test("books sorted by series groups by series then sequence then title") {
            runTest {
                // Given
                val books =
                    listOf(
                        createTestBook(id = "1", title = "Book A", seriesId = "alpha", seriesName = "Alpha Series", seriesSequence = 2.0),
                        createTestBook(id = "2", title = "Book B", seriesId = "alpha", seriesName = "Alpha Series", seriesSequence = 1.0),
                        createTestBook(id = "3", title = "Standalone", seriesName = null, seriesSequence = null),
                        createTestBook(id = "4", title = "Book C", seriesId = "beta", seriesName = "Beta Series", seriesSequence = 1.0),
                    )
                val fixture = createFixture()
                every { fixture.bookRepository.observeBookListItems() } returns flowOf(books)
                everySuspend { fixture.libraryPreferences.setBooksSortState(any()) } returns Unit
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.uiState.collect { } }
                advanceUntilIdle()

                // When - SERIES defaults to ASCENDING
                viewModel.onEvent(LibraryUiEvent.BooksCategoryChanged(SortCategory.SERIES))
                advanceUntilIdle()

                // Then - Series ASC: Alpha (seq 1, 2), Beta (seq 1), then null series at end
                val loaded = viewModel.uiState.value as LibraryUiState.Loaded
                loaded.books.map { it.title } shouldBe listOf("Book B", "Book A", "Book C", "Standalone")
            }
        }

        test("books sorted by series handles decimal sequences") {
            runTest {
                // Given
                val books =
                    listOf(
                        createTestBook(id = "1", title = "Book 2", seriesName = "Series", seriesSequence = 2.0),
                        createTestBook(id = "2", title = "Book 1.5", seriesName = "Series", seriesSequence = 1.5),
                        createTestBook(id = "3", title = "Book 1", seriesName = "Series", seriesSequence = 1.0),
                    )
                val fixture = createFixture()
                every { fixture.bookRepository.observeBookListItems() } returns flowOf(books)
                everySuspend { fixture.libraryPreferences.setBooksSortState(any()) } returns Unit
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.uiState.collect { } }
                advanceUntilIdle()

                // When - SERIES defaults to ASCENDING
                viewModel.onEvent(LibraryUiEvent.BooksCategoryChanged(SortCategory.SERIES))
                advanceUntilIdle()

                // Then - Should handle 1 < 1.5 < 2
                val loaded = viewModel.uiState.value as LibraryUiState.Loaded
                loaded.books.map { it.title } shouldBe listOf("Book 1", "Book 1.5", "Book 2")
            }
        }

        test("Listener rating sorts highest first, equal ratings by title, and unrated books come last in either direction") {
            runTest {
                // Given - a "Zebra" (avg 9), b (unrated), c (avg 5), d "apple" (avg 9, tied with a).
                // "apple" before "Zebra" only case-insensitively, and d comes after a in the input,
                // so the order below needs the title tie-breaker — a stable sort alone keeps a first.
                val books =
                    listOf(
                        createTestBook(id = "a", title = "Zebra"),
                        createTestBook(id = "b", title = "B"),
                        createTestBook(id = "c", title = "C"),
                        createTestBook(id = "d", title = "apple"),
                    )
                val averages =
                    mapOf(
                        "a" to ListenerAverage(averageHalfStars = 9.0, count = 1),
                        "c" to ListenerAverage(averageHalfStars = 5.0, count = 1),
                        "d" to ListenerAverage(averageHalfStars = 9.0, count = 2),
                    )
                val fixture = createFixture()
                every { fixture.bookRepository.observeBookListItems() } returns flowOf(books)
                every { fixture.bookRatingRepository.observeAverages() } returns flowOf(averages)
                everySuspend { fixture.libraryPreferences.setBooksSortState(any()) } returns Unit
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.uiState.collect { } }
                advanceUntilIdle()

                // When - LISTENER_RATING defaults to DESCENDING
                viewModel.onEvent(LibraryUiEvent.BooksCategoryChanged(SortCategory.LISTENER_RATING))
                advanceUntilIdle()

                // Then - DESC: d, a (tied, by title), c, b (unrated last)
                val descLoaded = viewModel.uiState.value as LibraryUiState.Loaded
                descLoaded.books.map { it.id.value } shouldBe listOf("d", "a", "c", "b")

                // When - toggle to ASCENDING
                viewModel.onEvent(LibraryUiEvent.BooksDirectionToggled)
                advanceUntilIdle()

                // Then - ASC: c, d, a (ties still by title), b (unrated STILL last, not first)
                val ascLoaded = viewModel.uiState.value as LibraryUiState.Loaded
                ascLoaded.books.map { it.id.value } shouldBe listOf("c", "d", "a", "b")
            }
        }

        test("Rating (the outside score) sorts highest first, equal scores by title, and unrated books come last in either direction") {
            runTest {
                // Same shape as the LISTENER_RATING test above, over observeCombinedScores instead.
                val books =
                    listOf(
                        createTestBook(id = "a", title = "Zebra"),
                        createTestBook(id = "b", title = "B"),
                        createTestBook(id = "c", title = "C"),
                        createTestBook(id = "d", title = "apple"),
                    )
                val scores =
                    mapOf(
                        "a" to CombinedScore(average = 4.5, count = 1),
                        "c" to CombinedScore(average = 3.0, count = 1),
                        "d" to CombinedScore(average = 4.5, count = 2),
                    )
                val fixture = createFixture()
                every { fixture.bookRepository.observeBookListItems() } returns flowOf(books)
                every { fixture.bookRatingRepository.observeCombinedScores() } returns flowOf(scores)
                everySuspend { fixture.libraryPreferences.setBooksSortState(any()) } returns Unit
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.uiState.collect { } }
                advanceUntilIdle()

                // When - RATING defaults to DESCENDING
                viewModel.onEvent(LibraryUiEvent.BooksCategoryChanged(SortCategory.RATING))
                advanceUntilIdle()

                // Then - DESC: d, a (tied, by title), c, b (unrated last)
                val descLoaded = viewModel.uiState.value as LibraryUiState.Loaded
                descLoaded.books.map { it.id.value } shouldBe listOf("d", "a", "c", "b")

                // When - toggle to ASCENDING
                viewModel.onEvent(LibraryUiEvent.BooksDirectionToggled)
                advanceUntilIdle()

                // Then - ASC: c, d, a (ties still by title), b (unrated STILL last, not first)
                val ascLoaded = viewModel.uiState.value as LibraryUiState.Loaded
                ascLoaded.books.map { it.id.value } shouldBe listOf("c", "d", "a", "b")
            }
        }

        // ========== Series Sorting Tests ==========

        test("series sorted by name ascending") {
            runTest {
                // Given - each series needs 2+ books to avoid filtering by hideSingleBookSeries
                val seriesList =
                    listOf(
                        SeriesWithBooks(
                            series = createTestSeries(id = "1", name = "Zebra Series"),
                            books = listOf(createDummyBook("z1"), createDummyBook("z2")),
                            bookSequences = emptyMap(),
                        ),
                        SeriesWithBooks(
                            series = createTestSeries(id = "2", name = "Apple Series"),
                            books = listOf(createDummyBook("a1"), createDummyBook("a2")),
                            bookSequences = emptyMap(),
                        ),
                    )
                val fixture = createFixture()
                every { fixture.seriesRepository.observeRootSeriesWithBooks() } returns flowOf(seriesList)
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.uiState.collect { } }
                advanceUntilIdle()

                // Then
                val loaded = viewModel.uiState.value as LibraryUiState.Loaded
                loaded.series.map { it.series.name } shouldBe listOf("Apple Series", "Zebra Series")
            }
        }

        test("series sorted by name ignores leading articles when enabled") {
            runTest {
                // "The Wandering Inn" must sort as "Wandering Inn" (under W, after Villains) — not under T.
                val seriesList =
                    listOf(
                        SeriesWithBooks(
                            series = createTestSeries(id = "1", name = "The Wandering Inn"),
                            books = listOf(createDummyBook("w1"), createDummyBook("w2")),
                            bookSequences = emptyMap(),
                        ),
                        SeriesWithBooks(
                            series = createTestSeries(id = "2", name = "Villains Code"),
                            books = listOf(createDummyBook("v1"), createDummyBook("v2")),
                            bookSequences = emptyMap(),
                        ),
                        SeriesWithBooks(
                            series = createTestSeries(id = "3", name = "Apple Saga"),
                            books = listOf(createDummyBook("a1"), createDummyBook("a2")),
                            bookSequences = emptyMap(),
                        ),
                    )
                val fixture = createFixture()
                everySuspend { fixture.libraryPreferences.getIgnoreTitleArticles() } returns true
                every { fixture.seriesRepository.observeRootSeriesWithBooks() } returns flowOf(seriesList)
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.uiState.collect { } }
                advanceUntilIdle()

                // Then - article stripped: Apple (A), Villains (V), The Wandering Inn (as Wandering, W)
                val loaded = viewModel.uiState.value as LibraryUiState.Loaded
                loaded.series.map { it.series.name } shouldBe
                    listOf("Apple Saga", "Villains Code", "The Wandering Inn")
            }
        }

        test("series sorted by book count descending") {
            runTest {
                // Given - each series needs 2+ books to avoid filtering by hideSingleBookSeries
                val seriesList =
                    listOf(
                        SeriesWithBooks(
                            series = createTestSeries(id = "1", name = "Small"),
                            books = listOf(createDummyBook("s1"), createDummyBook("s2")),
                            bookSequences = emptyMap(),
                        ),
                        SeriesWithBooks(
                            series = createTestSeries(id = "2", name = "Big"),
                            books =
                                listOf(
                                    createDummyBook("b1"),
                                    createDummyBook("b2"),
                                    createDummyBook("b3"),
                                ),
                            bookSequences = emptyMap(),
                        ),
                    )
                val fixture = createFixture()
                every { fixture.seriesRepository.observeRootSeriesWithBooks() } returns flowOf(seriesList)
                everySuspend { fixture.libraryPreferences.setSeriesSortState(any()) } returns Unit
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.uiState.collect { } }
                advanceUntilIdle()

                // When
                viewModel.onEvent(LibraryUiEvent.SeriesCategoryChanged(SortCategory.BOOK_COUNT))
                advanceUntilIdle()

                // Then - Book count DESC: most books first
                val loaded = viewModel.uiState.value as LibraryUiState.Loaded
                loaded.series.map { it.series.name } shouldBe listOf("Big", "Small")
            }
        }

        // ========== Contributors Sorting Tests ==========

        test("authors sorted by name ascending") {
            runTest {
                // Given
                val authors =
                    listOf(
                        createTestContributor(id = "1", name = "Zelda"),
                        createTestContributor(id = "2", name = "Adam"),
                    )
                val fixture = createFixture()
                every { fixture.contributorRepository.observeContributorsByRole(ContributorRole.AUTHOR.apiValue) } returns
                    flowOf(authors)
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.uiState.collect { } }
                advanceUntilIdle()

                // Then
                val loaded = viewModel.uiState.value as LibraryUiState.Loaded
                loaded.authors.map { it.contributor.name } shouldBe listOf("Adam", "Zelda")
            }
        }

        test("authors sorted by book count descending") {
            runTest {
                // Given
                val authors =
                    listOf(
                        createTestContributor(id = "1", name = "Few Books", bookCount = 2),
                        createTestContributor(id = "2", name = "Many Books", bookCount = 10),
                    )
                val fixture = createFixture()
                every { fixture.contributorRepository.observeContributorsByRole(ContributorRole.AUTHOR.apiValue) } returns
                    flowOf(authors)
                everySuspend { fixture.libraryPreferences.setAuthorsSortState(any()) } returns Unit
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.uiState.collect { } }
                advanceUntilIdle()

                // When
                viewModel.onEvent(LibraryUiEvent.AuthorsCategoryChanged(SortCategory.BOOK_COUNT))
                advanceUntilIdle()

                // Then
                val loaded = viewModel.uiState.value as LibraryUiState.Loaded
                loaded.authors.map { it.contributor.name } shouldBe listOf("Many Books", "Few Books")
            }
        }

        // ========== Auto-Sync Tests ==========

        test("onScreenVisible triggers sync when authenticated and never synced") {
            runTest {
                // Given
                val fixture = createFixture()
                everySuspend { fixture.authSession.getAccessToken() } returns AccessToken("token")
                everySuspend { fixture.syncStatusRepository.getLastSyncTime() } returns null // Never synced
                everySuspend { fixture.bookRepository.refreshBooks() } returns AppResult.Success(Unit)
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.uiState.collect { } }
                advanceUntilIdle()

                // When
                viewModel.onScreenVisible()
                advanceUntilIdle()

                // Then
                verifySuspend { fixture.bookRepository.refreshBooks() }
            }
        }

        test("onScreenVisible does not sync when not authenticated") {
            runTest {
                // Given
                val fixture = createFixture()
                everySuspend { fixture.authSession.getAccessToken() } returns null // Not authenticated
                everySuspend { fixture.syncStatusRepository.getLastSyncTime() } returns null
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.uiState.collect { } }
                advanceUntilIdle()

                // When
                viewModel.onScreenVisible()
                advanceUntilIdle()

                // Then - refreshBooks should not be called (verified indirectly via absence of exception)
            }
        }

        // ========== Book Progress Tests ==========

        test("bookStatus carries each started book's fraction and time left") {
            runTest {
                // Given
                val books =
                    listOf(
                        createTestBook(id = "book-1", duration = 10_000L),
                        createTestBook(id = "book-2", duration = 20_000L),
                    )
                val positions =
                    mapOf(
                        BookId("book-1") to
                            PlaybackPosition(
                                bookId = "book-1",
                                positionMs = 5_000L, // 50% progress
                                playbackSpeed = 1.0f,
                                hasCustomSpeed = false,
                                volumeBoostDb = 0f,
                                hasCustomBoost = false,
                                measuredGainDb = null,
                                updatedAtMs = 0L,
                                syncedAtMs = null,
                                lastPlayedAtMs = null,
                            ),
                        BookId("book-2") to
                            PlaybackPosition(
                                bookId = "book-2",
                                positionMs = 10_000L, // 50% progress
                                playbackSpeed = 1.0f,
                                hasCustomSpeed = false,
                                volumeBoostDb = 0f,
                                hasCustomBoost = false,
                                measuredGainDb = null,
                                updatedAtMs = 0L,
                                syncedAtMs = null,
                                lastPlayedAtMs = null,
                            ),
                    )
                val fixture = createFixture()
                every { fixture.bookRepository.observeBookListItems() } returns flowOf(books)
                every { fixture.playbackPositionRepository.observeAll() } returns flowOf(positions)
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.uiState.collect { } }
                advanceUntilIdle()

                // Then
                val loaded = viewModel.uiState.value as LibraryUiState.Loaded
                loaded.bookStatus[BookId("book-1")] shouldBe BookCardStatus.InProgress(fraction = 0.5f, timeLeftMs = 5_000L)
                loaded.bookStatus[BookId("book-2")] shouldBe BookCardStatus.InProgress(fraction = 0.5f, timeLeftMs = 10_000L)
            }
        }

        test("a finished book's card status is Finished") {
            runTest {
                // Given - a book marked finished (authoritative from the server); the card shows the
                // finished badge and "Finished · length" instead of a progress mark
                val books =
                    listOf(
                        createTestBook(id = "book-1", duration = 10_000L),
                    )
                val positions =
                    mapOf(
                        BookId("book-1") to
                            PlaybackPosition(
                                bookId = "book-1",
                                positionMs = 9_950L,
                                playbackSpeed = 1.0f,
                                hasCustomSpeed = false,
                                volumeBoostDb = 0f,
                                hasCustomBoost = false,
                                measuredGainDb = null,
                                updatedAtMs = 0L,
                                syncedAtMs = null,
                                lastPlayedAtMs = null,
                                isFinished = true,
                            ),
                    )
                val fixture = createFixture()
                every { fixture.bookRepository.observeBookListItems() } returns flowOf(books)
                every { fixture.playbackPositionRepository.observeAll() } returns flowOf(positions)
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.uiState.collect { } }
                advanceUntilIdle()

                // Then - the card says Finished, with the book's length
                val loaded = viewModel.uiState.value as LibraryUiState.Loaded
                loaded.bookStatus[BookId("book-1")] shouldBe BookCardStatus.Finished(durationMs = 10_000L)
            }
        }

        // ========== In progress filter Tests ==========

        test("the In progress filter shows only started, unfinished books") {
            runTest {
                // Given — three books: not started, in progress, finished
                val books =
                    listOf(
                        createTestBook(id = "not-started", duration = 10_000L),
                        createTestBook(id = "in-progress", duration = 10_000L),
                        createTestBook(id = "finished", duration = 10_000L),
                    )
                val positions =
                    mapOf(
                        // "in-progress" has 50% progress and is not finished
                        BookId("in-progress") to
                            PlaybackPosition(
                                bookId = "in-progress",
                                positionMs = 5_000L,
                                playbackSpeed = 1.0f,
                                hasCustomSpeed = false,
                                volumeBoostDb = 0f,
                                hasCustomBoost = false,
                                measuredGainDb = null,
                                updatedAtMs = 0L,
                                syncedAtMs = null,
                                lastPlayedAtMs = null,
                                isFinished = false,
                            ),
                        // "finished" is marked finished (isFinished = true)
                        BookId("finished") to
                            PlaybackPosition(
                                bookId = "finished",
                                positionMs = 10_000L,
                                playbackSpeed = 1.0f,
                                hasCustomSpeed = false,
                                volumeBoostDb = 0f,
                                hasCustomBoost = false,
                                measuredGainDb = null,
                                updatedAtMs = 0L,
                                syncedAtMs = null,
                                lastPlayedAtMs = null,
                                isFinished = true,
                            ),
                    )
                val fixture = createFixture()
                every { fixture.bookRepository.observeBookListItems() } returns flowOf(books)
                every { fixture.playbackPositionRepository.observeAll() } returns flowOf(positions)
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.uiState.collect { } }
                viewModel.onEvent(LibraryUiEvent.StatusFilterChanged(BookStatusFilter.IN_PROGRESS))
                advanceUntilIdle()

                // Then — only the in-progress book appears
                val loaded = viewModel.uiState.value as LibraryUiState.Loaded
                loaded.books.map { it.id.value } shouldBe listOf("in-progress")
            }
        }

        test("the In progress filter is empty, not the library, when no book has partial progress") {
            runTest {
                // Given — one book, no positions
                val books = listOf(createTestBook(id = "book-1", duration = 10_000L))
                val fixture = createFixture()
                every { fixture.bookRepository.observeBookListItems() } returns flowOf(books)
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.uiState.collect { } }
                viewModel.onEvent(LibraryUiEvent.StatusFilterChanged(BookStatusFilter.IN_PROGRESS))
                advanceUntilIdle()

                // Then
                val loaded = viewModel.uiState.value as LibraryUiState.Loaded
                loaded.books shouldBe emptyList()
                loaded.isFilteredEmpty shouldBe true
            }
        }

        // ========== Per-Upstream Catch Tests ==========

        test("transient observeBooks failure keeps state as Loaded with empty books") {
            runTest {
                // Given - book repository flow throws on first collect; per-upstream .catch emits empty list
                val fixture = createFixture()
                every { fixture.bookRepository.observeBookListItems() } returns flow { throw SimulatedFailure("transient") }
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.uiState.collect { } }
                advanceUntilIdle()

                // Then - state degrades gracefully to Loaded with empty books rather than Error
                val loaded = viewModel.uiState.value as LibraryUiState.Loaded
                loaded.books.size shouldBe 0
            }
        }

        test("transient observeAll(positions) failure keeps state as Loaded with empty progress") {
            runTest {
                // Given - playback position flow throws on first collect; per-upstream .catch emits empty map
                val fixture = createFixture()
                every { fixture.playbackPositionRepository.observeAll() } returns flow { throw SimulatedFailure("transient") }
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.uiState.collect { } }
                advanceUntilIdle()

                // Then - state degrades gracefully to Loaded with empty progress maps rather than Error
                val loaded = viewModel.uiState.value as LibraryUiState.Loaded
                loaded.bookStatus.size shouldBe 0
                loaded.statusCounts.all shouldBe 0
            }
        }

        test("RefreshRequested after the pipeline failed restarts it and leaves Error") {
            // ⛔ The regression: Error is what the pipeline's own fallback emits as it TERMINATES, so
            // before this fix the Retry button on web and Android re-synced books into Room while the
            // screen sat on "can't be shown" for good — only leaving and re-entering it after the
            // subscription timeout could bring the library back.
            runTest {
                val fixture = createFixture()
                // The scan flag is one of the upstreams with no per-source fallback, so a throw here
                // reaches the pipeline-level catch. Fails on its first collection, healthy after.
                every { fixture.syncRepository.serverScanning } returns FailsOnFirstCollect(false)
                everySuspend { fixture.bookRepository.refreshBooks() } returns AppResult.Success(Unit)
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.uiState.collect { } }
                advanceUntilIdle()
                viewModel.uiState.value.shouldBeInstanceOf<LibraryUiState.Error>()

                viewModel.onEvent(LibraryUiEvent.RefreshRequested)
                advanceUntilIdle()

                viewModel.uiState.value.shouldBeInstanceOf<LibraryUiState.Loaded>()
                verifySuspend { fixture.bookRepository.refreshBooks() }
            }
        }

        // ========== SeriesProgress Tests ==========

        test("seriesProgress aggregates finished books per series") {
            runTest {
                // Given - 3-book series with 1 finished book
                val books =
                    listOf(
                        createTestBook(id = "b1", seriesId = "s1", seriesName = "Test Series", seriesSequence = 1.0),
                        createTestBook(id = "b2", seriesId = "s1", seriesName = "Test Series", seriesSequence = 2.0),
                        createTestBook(id = "b3", seriesId = "s1", seriesName = "Test Series", seriesSequence = 3.0),
                    )
                val seriesList =
                    listOf(
                        SeriesWithBooks(
                            series = createTestSeries(id = "s1"),
                            books = books,
                            bookSequences = mapOf("b1" to 1.0, "b2" to 2.0, "b3" to 3.0),
                        ),
                    )
                val positions =
                    mapOf(
                        BookId("b1") to
                            PlaybackPosition(
                                bookId = "b1",
                                positionMs = 0L,
                                playbackSpeed = 1.0f,
                                hasCustomSpeed = false,
                                volumeBoostDb = 0f,
                                hasCustomBoost = false,
                                measuredGainDb = null,
                                updatedAtMs = 0L,
                                syncedAtMs = null,
                                lastPlayedAtMs = null,
                                isFinished = true,
                            ),
                    )
                val fixture = createFixture()
                // hideSingleBookSeries is true by default; override to false or use 3-book series
                // The 3-book series is already > 1, so it passes the filter.
                every { fixture.seriesRepository.observeRootSeriesWithBooks() } returns flowOf(seriesList)
                every { fixture.playbackPositionRepository.observeAll() } returns flowOf(positions)
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.uiState.collect { } }
                advanceUntilIdle()

                // Then
                val loaded = viewModel.uiState.value as LibraryUiState.Loaded
                val progress =
                    loaded.seriesProgress[
                        com.calypsan.listenup.core
                            .SeriesId("s1"),
                    ]
                        ?: error("Expected seriesProgress entry for s1")
                progress.finishedCount shouldBe 1
                progress.totalCount shouldBe 3
                progress.isComplete shouldBe false
                progress.isNotStarted shouldBe false
            }
        }

        // ========== distinctUntilChanged / conflate smoke tests ==========

        // Regression guard: a real SyncState change (Idle → Syncing) propagates end-to-end through
        // the syncSnapshot → uiState pipeline. We assert on the settled uiState.value rather than
        // counting emissions — conflate() makes intermediate emission counts non-deterministic, but
        // the converged value is stable (mirroring the value-based assertion every other test here
        // uses).
        test("changed syncState propagates into uiState") {
            runTest {
                // Given
                val fixture = createFixture()
                val syncStateFlow = MutableStateFlow<SyncState>(SyncState.Idle)
                every { fixture.syncRepository.syncState } returns syncStateFlow

                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.uiState.collect { } }
                advanceUntilIdle()
                (viewModel.uiState.value as LibraryUiState.Loaded).syncState shouldBe SyncState.Idle

                // When — emit a genuinely different SyncState
                syncStateFlow.value = SyncState.Syncing
                advanceUntilIdle()

                // Then — the change is reflected in the settled uiState
                val loaded = viewModel.uiState.value as LibraryUiState.Loaded
                loaded.syncState shouldBe SyncState.Syncing
            }
        }

        // ========== Progress-overlay tests (no re-sort on position ticks) ==========

        test("progress-only update preserves sorted list identity (no re-sort)") {
            runTest {
                // Given — two books, positions arrive via a hot flow so we can tick them later
                val books =
                    listOf(
                        createTestBook(id = "1", title = "Zebra", duration = 10_000L),
                        createTestBook(id = "2", title = "Apple", duration = 10_000L),
                    )

                // Book 1 is already in progress, so the tick below moves its progress without moving it
                // across a reading-state boundary (a boundary crossing legitimately re-sorts: it can move
                // the book in or out of a status filter).
                fun startedAt(positionMs: Long) =
                    PlaybackPosition(
                        bookId = "1",
                        positionMs = positionMs,
                        playbackSpeed = 1.0f,
                        hasCustomSpeed = false,
                        volumeBoostDb = 0f,
                        hasCustomBoost = false,
                        measuredGainDb = null,
                        updatedAtMs = 0L,
                        syncedAtMs = null,
                        lastPlayedAtMs = null,
                    )
                val positionsFlow = MutableStateFlow(mapOf(BookId("1") to startedAt(1_000L)))
                val fixture = createFixture()
                every { fixture.bookRepository.observeBookListItems() } returns flowOf(books)
                every { fixture.playbackPositionRepository.observeAll() } returns positionsFlow
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.uiState.collect { } }
                advanceUntilIdle()
                val before = viewModel.uiState.value as LibraryUiState.Loaded
                before.books.map { it.title } shouldBe listOf("Apple", "Zebra")

                // When — a playback-position tick arrives (progress-only change)
                positionsFlow.value =
                    mapOf(
                        BookId("1") to
                            PlaybackPosition(
                                bookId = "1",
                                positionMs = 5_000L, // 50%
                                playbackSpeed = 1.0f,
                                hasCustomSpeed = false,
                                volumeBoostDb = 0f,
                                hasCustomBoost = false,
                                measuredGainDb = null,
                                updatedAtMs = 0L,
                                syncedAtMs = null,
                                lastPlayedAtMs = null,
                            ),
                    )
                advanceUntilIdle()

                // Then — progress IS reflected in new state...
                val after = viewModel.uiState.value as LibraryUiState.Loaded
                after.bookStatus[BookId("1")] shouldBe BookCardStatus.InProgress(fraction = 0.5f, timeLeftMs = 5_000L)
                // ...but the sorted lists are the SAME instances: the sort stage did not
                // re-run (every applicable sort branch allocates a fresh list via .map).
                after.books shouldBeSameInstanceAs before.books
                after.series shouldBeSameInstanceAs before.series
                after.authors shouldBeSameInstanceAs before.authors
                after.narrators shouldBeSameInstanceAs before.narrators
            }
        }

        test("content change still re-sorts books") {
            runTest {
                // Given — books arrive via a hot shared flow so we can push a second list
                val booksFlow = MutableSharedFlow<List<BookListItem>>(replay = 1)
                booksFlow.tryEmit(
                    listOf(
                        createTestBook(id = "1", title = "Zebra"),
                        createTestBook(id = "2", title = "Apple"),
                    ),
                )
                val fixture = createFixture()
                every { fixture.bookRepository.observeBookListItems() } returns booksFlow
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.uiState.collect { } }
                advanceUntilIdle()
                (viewModel.uiState.value as LibraryUiState.Loaded).books.map { it.title } shouldBe
                    listOf("Apple", "Zebra")

                // When — a new book appears
                booksFlow.tryEmit(
                    listOf(
                        createTestBook(id = "1", title = "Zebra"),
                        createTestBook(id = "2", title = "Apple"),
                        createTestBook(id = "3", title = "Mango"),
                    ),
                )
                advanceUntilIdle()

                // Then — the new list is sorted with the new content
                val loaded = viewModel.uiState.value as LibraryUiState.Loaded
                loaded.books.map { it.title } shouldBe listOf("Apple", "Mango", "Zebra")
            }
        }

        test("structurally equal content re-emission preserves sorted list identity") {
            runTest {
                // Given — Room-style re-emission: same content, new list instance.
                // MutableSharedFlow (unlike MutableStateFlow) does not dedupe equal values.
                val booksFlow = MutableSharedFlow<List<BookListItem>>(replay = 1)
                val makeBooks = {
                    listOf(
                        createTestBook(id = "1", title = "Zebra"),
                        createTestBook(id = "2", title = "Apple"),
                    )
                }
                booksFlow.tryEmit(makeBooks())
                val fixture = createFixture()
                every { fixture.bookRepository.observeBookListItems() } returns booksFlow
                val viewModel = fixture.build()
                backgroundScope.launch { viewModel.uiState.collect { } }
                advanceUntilIdle()
                val before = viewModel.uiState.value as LibraryUiState.Loaded

                // When — an equal-but-new list is re-emitted
                booksFlow.tryEmit(makeBooks())
                advanceUntilIdle()

                // Then — distinctUntilChanged on the sorted stage swallows the no-op
                val after = viewModel.uiState.value as LibraryUiState.Loaded
                after.books shouldBeSameInstanceAs before.books
            }
        }

        // ========== Content revision (lets a consumer skip re-mapping unchanged lists) ==========

        context("content revision") {
            fun positionAt(
                bookId: String,
                positionMs: Long,
            ) = PlaybackPosition(
                bookId = bookId,
                positionMs = positionMs,
                playbackSpeed = 1.0f,
                hasCustomSpeed = false,
                volumeBoostDb = 0f,
                hasCustomBoost = false,
                measuredGainDb = null,
                updatedAtMs = 0L,
                syncedAtMs = null,
                lastPlayedAtMs = null,
            )

            test("a progress change keeps the content revision") {
                runTest {
                    // Already in progress: a tick within a reading state is the case that must not
                    // bump the revision. Crossing a state boundary does (see "status filters").
                    val positionsFlow = MutableStateFlow(mapOf(BookId("1") to positionAt("1", 1_000L)))
                    val fixture = createFixture()
                    every { fixture.bookRepository.observeBookListItems() } returns
                        flowOf(listOf(createTestBook(id = "1", duration = 10_000L)))
                    every { fixture.playbackPositionRepository.observeAll() } returns positionsFlow
                    val viewModel = fixture.build()

                    turbineScope {
                        val states = viewModel.uiState.testIn(backgroundScope)
                        advanceUntilIdle()
                        val before = states.expectMostRecentItem().shouldBeInstanceOf<LibraryUiState.Loaded>()

                        positionsFlow.value = mapOf(BookId("1") to positionAt("1", 5_000L))
                        advanceUntilIdle()

                        val after = states.expectMostRecentItem().shouldBeInstanceOf<LibraryUiState.Loaded>()
                        after.bookStatus[BookId("1")] shouldBe BookCardStatus.InProgress(fraction = 0.5f, timeLeftMs = 5_000L)
                        after.contentRevision shouldBe before.contentRevision
                        states.cancel()
                    }
                }
            }

            test("a sync change keeps the content revision") {
                runTest {
                    val fixture = createFixture()
                    every { fixture.bookRepository.observeBookListItems() } returns
                        flowOf(listOf(createTestBook(id = "1")))
                    val viewModel = fixture.build()

                    turbineScope {
                        val states = viewModel.uiState.testIn(backgroundScope)
                        advanceUntilIdle()
                        val before = states.expectMostRecentItem().shouldBeInstanceOf<LibraryUiState.Loaded>()

                        fixture.syncStateFlow.value = SyncState.Syncing
                        advanceUntilIdle()

                        val after = states.expectMostRecentItem().shouldBeInstanceOf<LibraryUiState.Loaded>()
                        after.syncState shouldBe SyncState.Syncing
                        after.contentRevision shouldBe before.contentRevision
                        states.cancel()
                    }
                }
            }

            test("a structurally equal content re-emission keeps the content revision") {
                runTest {
                    val booksFlow = MutableSharedFlow<List<BookListItem>>(replay = 1)
                    booksFlow.tryEmit(listOf(createTestBook(id = "1", title = "Zebra")))
                    val fixture = createFixture()
                    every { fixture.bookRepository.observeBookListItems() } returns booksFlow
                    val viewModel = fixture.build()

                    turbineScope {
                        val states = viewModel.uiState.testIn(backgroundScope)
                        advanceUntilIdle()
                        val before = states.expectMostRecentItem().shouldBeInstanceOf<LibraryUiState.Loaded>()

                        booksFlow.tryEmit(listOf(createTestBook(id = "1", title = "Zebra")))
                        advanceUntilIdle()

                        val after = viewModel.uiState.value.shouldBeInstanceOf<LibraryUiState.Loaded>()
                        after.contentRevision shouldBe before.contentRevision
                        states.cancel()
                    }
                }
            }

            test("a content change advances the content revision") {
                runTest {
                    val booksFlow = MutableSharedFlow<List<BookListItem>>(replay = 1)
                    booksFlow.tryEmit(listOf(createTestBook(id = "1", title = "Zebra")))
                    val fixture = createFixture()
                    every { fixture.bookRepository.observeBookListItems() } returns booksFlow
                    val viewModel = fixture.build()

                    turbineScope {
                        val states = viewModel.uiState.testIn(backgroundScope)
                        advanceUntilIdle()
                        val before = states.expectMostRecentItem().shouldBeInstanceOf<LibraryUiState.Loaded>()

                        booksFlow.tryEmit(
                            listOf(createTestBook(id = "1", title = "Zebra"), createTestBook(id = "2", title = "Apple")),
                        )
                        advanceUntilIdle()

                        val after = states.expectMostRecentItem().shouldBeInstanceOf<LibraryUiState.Loaded>()
                        after.books.map { it.title } shouldBe listOf("Apple", "Zebra")
                        (after.contentRevision > before.contentRevision) shouldBe true
                        states.cancel()
                    }
                }
            }

            test("a sort change advances the content revision") {
                runTest {
                    val fixture = createFixture()
                    every { fixture.bookRepository.observeBookListItems() } returns
                        flowOf(listOf(createTestBook(id = "1", title = "Zebra"), createTestBook(id = "2", title = "Apple")))
                    everySuspend { fixture.libraryPreferences.setBooksSortState(any()) } returns Unit
                    val viewModel = fixture.build()

                    turbineScope {
                        val states = viewModel.uiState.testIn(backgroundScope)
                        advanceUntilIdle()
                        val before = states.expectMostRecentItem().shouldBeInstanceOf<LibraryUiState.Loaded>()

                        viewModel.onEvent(LibraryUiEvent.BooksDirectionToggled)
                        advanceUntilIdle()

                        val after = states.expectMostRecentItem().shouldBeInstanceOf<LibraryUiState.Loaded>()
                        after.books.map { it.title } shouldBe listOf("Zebra", "Apple")
                        (after.contentRevision > before.contentRevision) shouldBe true
                        states.cancel()
                    }
                }
            }
        }

        context("status filters") {
            fun at(
                id: String,
                positionMs: Long,
                isFinished: Boolean = false,
            ) = BookId(id) to
                PlaybackPosition(
                    bookId = id,
                    positionMs = positionMs,
                    playbackSpeed = 1.0f,
                    hasCustomSpeed = false,
                    volumeBoostDb = 0f,
                    hasCustomBoost = false,
                    measuredGainDb = null,
                    updatedAtMs = 0L,
                    syncedAtMs = null,
                    lastPlayedAtMs = null,
                    isFinished = isFinished,
                )

            // b1 in progress · b2 finished · b3 a zero position (not started) · b4 no position (not started)
            val mixedBooks =
                listOf(
                    createTestBook(id = "b1", title = "Alpha", duration = 10_000L),
                    createTestBook(id = "b2", title = "Bravo", duration = 20_000L),
                    createTestBook(id = "b3", title = "Charlie", duration = 30_000L),
                    createTestBook(id = "b4", title = "Delta", duration = 40_000L),
                )
            val mixedPositions = mapOf(at("b1", 4_000L), at("b2", 20_000L, isFinished = true), at("b3", 0L))

            fun TestFixture.withMixedLibrary(positions: MutableStateFlow<Map<BookId, PlaybackPosition>>) {
                every { bookRepository.observeBookListItems() } returns flowOf(mixedBooks)
                every { playbackPositionRepository.observeAll() } returns positions
            }

            test("the counts are right for a mixed library and cover the whole library") {
                runTest {
                    val fixture = createFixture().apply { withMixedLibrary(MutableStateFlow(mixedPositions)) }
                    val viewModel = fixture.build()
                    backgroundScope.launch { viewModel.uiState.collect { } }
                    advanceUntilIdle()

                    val loaded = viewModel.uiState.value.shouldBeInstanceOf<LibraryUiState.Loaded>()
                    loaded.statusFilter shouldBe BookStatusFilter.ALL
                    loaded.statusCounts shouldBe BookStatusCounts(all = 4, inProgress = 1, notStarted = 2, finished = 1)
                    loaded.books.map { it.id.value } shouldBe listOf("b1", "b2", "b3", "b4")
                }
            }

            test("a filter change emits once, with the filtered books and a new revision; counts stay whole") {
                runTest {
                    val fixture = createFixture().apply { withMixedLibrary(MutableStateFlow(mixedPositions)) }
                    val viewModel = fixture.build()

                    turbineScope {
                        val states = viewModel.uiState.testIn(backgroundScope)
                        advanceUntilIdle()
                        val before = states.expectMostRecentItem().shouldBeInstanceOf<LibraryUiState.Loaded>()

                        viewModel.onEvent(LibraryUiEvent.StatusFilterChanged(BookStatusFilter.NOT_STARTED))
                        advanceUntilIdle()

                        val after = states.awaitItem().shouldBeInstanceOf<LibraryUiState.Loaded>()
                        states.expectNoEvents()
                        after.statusFilter shouldBe BookStatusFilter.NOT_STARTED
                        after.books.map { it.id.value } shouldBe listOf("b3", "b4")
                        after.statusCounts shouldBe before.statusCounts
                        (after.contentRevision > before.contentRevision) shouldBe true
                        states.cancel()
                    }
                }
            }

            test("a position tick that keeps every status emits without a revision bump, and moves the card") {
                runTest {
                    val positions = MutableStateFlow(mixedPositions)
                    val fixture = createFixture().apply { withMixedLibrary(positions) }
                    val viewModel = fixture.build()

                    turbineScope {
                        val states = viewModel.uiState.testIn(backgroundScope)
                        advanceUntilIdle()
                        val before = states.expectMostRecentItem().shouldBeInstanceOf<LibraryUiState.Loaded>()

                        positions.value = mixedPositions + at("b1", 6_000L)
                        advanceUntilIdle()

                        val after = states.expectMostRecentItem().shouldBeInstanceOf<LibraryUiState.Loaded>()
                        after.bookStatus[BookId("b1")] shouldBe BookCardStatus.InProgress(fraction = 0.6f, timeLeftMs = 4_000L)
                        after.contentRevision shouldBe before.contentRevision
                        after.books shouldBeSameInstanceAs before.books
                        states.cancel()
                    }
                }
            }

            test("a book crossing to Finished leaves In progress and bumps the revision") {
                runTest {
                    val positions = MutableStateFlow(mixedPositions)
                    val fixture = createFixture().apply { withMixedLibrary(positions) }
                    val viewModel = fixture.build()

                    turbineScope {
                        val states = viewModel.uiState.testIn(backgroundScope)
                        viewModel.onEvent(LibraryUiEvent.StatusFilterChanged(BookStatusFilter.IN_PROGRESS))
                        advanceUntilIdle()
                        val before = states.expectMostRecentItem().shouldBeInstanceOf<LibraryUiState.Loaded>()
                        before.books.map { it.id.value } shouldBe listOf("b1")

                        positions.value = mixedPositions + at("b1", 10_000L, isFinished = true)
                        advanceUntilIdle()

                        val after = states.expectMostRecentItem().shouldBeInstanceOf<LibraryUiState.Loaded>()
                        after.books shouldBe emptyList()
                        after.statusCounts shouldBe BookStatusCounts(all = 4, inProgress = 0, notStarted = 2, finished = 2)
                        after.bookStatus[BookId("b1")] shouldBe BookCardStatus.Finished(durationMs = 10_000L)
                        (after.contentRevision > before.contentRevision) shouldBe true
                        after.isEmpty shouldBe false
                        after.isFilteredEmpty shouldBe true
                        states.cancel()
                    }
                }
            }

            test("totalDurationMs is the whole library's length, whatever the filter") {
                runTest {
                    val fixture = createFixture().apply { withMixedLibrary(MutableStateFlow(mixedPositions)) }
                    val viewModel = fixture.build()
                    backgroundScope.launch { viewModel.uiState.collect { } }
                    viewModel.onEvent(LibraryUiEvent.StatusFilterChanged(BookStatusFilter.FINISHED))
                    advanceUntilIdle()

                    val loaded = viewModel.uiState.value.shouldBeInstanceOf<LibraryUiState.Loaded>()
                    loaded.totalDurationMs shouldBe 100_000L
                }
            }

            test("every book has a card status, filtered or not") {
                runTest {
                    val fixture = createFixture().apply { withMixedLibrary(MutableStateFlow(mixedPositions)) }
                    val viewModel = fixture.build()
                    backgroundScope.launch { viewModel.uiState.collect { } }
                    viewModel.onEvent(LibraryUiEvent.StatusFilterChanged(BookStatusFilter.FINISHED))
                    advanceUntilIdle()

                    val loaded = viewModel.uiState.value.shouldBeInstanceOf<LibraryUiState.Loaded>()
                    loaded.bookStatus shouldBe
                        mapOf(
                            BookId("b1") to BookCardStatus.InProgress(fraction = 0.4f, timeLeftMs = 6_000L),
                            BookId("b2") to BookCardStatus.Finished(durationMs = 20_000L),
                            BookId("b3") to BookCardStatus.NotStarted(durationMs = 30_000L),
                            BookId("b4") to BookCardStatus.NotStarted(durationMs = 40_000L),
                        )
                }
            }

            test("an empty library is empty, not filtered-empty") {
                runTest {
                    val viewModel = createFixture().build()
                    backgroundScope.launch { viewModel.uiState.collect { } }
                    advanceUntilIdle()

                    val loaded = viewModel.uiState.value.shouldBeInstanceOf<LibraryUiState.Loaded>()
                    loaded.isEmpty shouldBe true
                    loaded.isFilteredEmpty shouldBe false
                }
            }

            test("a new ViewModel starts on All: the filter is never persisted") {
                runTest {
                    val fixture = createFixture().apply { withMixedLibrary(MutableStateFlow(mixedPositions)) }
                    val first = fixture.build()
                    backgroundScope.launch { first.uiState.collect { } }
                    first.onEvent(LibraryUiEvent.StatusFilterChanged(BookStatusFilter.FINISHED))
                    advanceUntilIdle()

                    val second = fixture.build()
                    backgroundScope.launch { second.uiState.collect { } }
                    advanceUntilIdle()

                    second.uiState.value
                        .shouldBeInstanceOf<LibraryUiState.Loaded>()
                        .statusFilter shouldBe BookStatusFilter.ALL
                }
            }
        }

        context("lifecycle") {
            // iOS has no ViewModelStore, so the CarPlay wrapper closes the VM from its
            // `isolated deinit` (#1192) — the same contract every other iOS-consumed VM carries.
            test("close cancels the viewModelScope and is idempotent") {
                runTest(testDispatcher) {
                    val viewModel = createFixture().build()

                    viewModel.close()
                    viewModel.close() // second call must be a no-op, not a crash

                    viewModel.viewModelScope.isActive shouldBe false
                }
            }
        }
    })

/**
 * A [StateFlow] whose first collection throws and whose later ones behave — the shape of an upstream
 * that failed once and recovered. A StateFlow because that is what `SyncRepository` exposes.
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class FailsOnFirstCollect<T>(
    initial: T,
    private val delegate: MutableStateFlow<T> = MutableStateFlow(initial),
) : StateFlow<T> by delegate {
    private var collections = 0

    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        collections++
        if (collections == 1) throw SimulatedFailure("transient")
        delegate.collect(collector)
    }
}
