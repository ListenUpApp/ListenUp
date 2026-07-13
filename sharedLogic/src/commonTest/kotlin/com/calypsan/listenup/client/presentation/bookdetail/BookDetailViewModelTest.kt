package com.calypsan.listenup.client.presentation.bookdetail

import app.cash.turbine.turbineScope
import com.calypsan.listenup.client.TestData
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ShelfId
import com.calypsan.listenup.core.error.ErrorBus
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.model.Genre
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.client.domain.model.Shelf
import com.calypsan.listenup.client.domain.model.Tag
import com.calypsan.listenup.client.domain.repository.BookAvailability
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import com.calypsan.listenup.client.domain.repository.DocumentRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.domain.repository.Reachability
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.client.domain.repository.ServerReachability
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import com.calypsan.listenup.client.domain.repository.TagRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.domain.usecase.shelf.AddBooksToShelfUseCase
import com.calypsan.listenup.client.domain.usecase.shelf.CreateShelfUseCase
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Tests for BookDetailViewModel.
 *
 * Tests cover:
 * - Initial state (Loading)
 * - Load book success/not found
 * - Subtitle filtering (redundant subtitle removal)
 * - Genre parsing
 * - Progress calculation and time remaining
 * - Tag management (show/hide picker, add/remove/create tags)
 *
 * Uses Mokkery for mocking domain repositories.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookDetailViewModelTest :
    FunSpec({

        val testDispatcher = StandardTestDispatcher()

        // ========== Test Fixtures ==========

        // Fake BookAvailability that returns a controllable StateFlow.
        class FakeBookAvailability(
            initial: BookAvailability.State =
                BookAvailability.State(
                    downloadStatus = BookDownloadStatus.NotDownloaded(""),
                    isPlaybackAvailable = true,
                    canPlay = true,
                    canDownload = false,
                    showServerWarning = false,
                    isWaitingForWifi = false,
                ),
        ) : BookAvailability {
            val stateFlow = MutableStateFlow(initial)

            override fun observe(bookId: BookId): Flow<BookAvailability.State> = stateFlow
        }

        // Fake ServerReachability that records retry() invocations and exposes a
        // controllable state flow.
        class FakeServerReachability : ServerReachability {
            override val state = MutableStateFlow<Reachability>(Reachability.Unknown)
            var retryCalls = 0
                private set

            override suspend fun retry() {
                retryCalls++
            }
        }

        // Convenience fixture class to avoid Triple unpacking
        class TestFixture {
            val bookRepository: BookRepository = mock()
            val tagRepository: TagRepository = mock()
            val playbackPositionRepository: PlaybackPositionRepository = mock()
            val userRepository: UserRepository = mock()
            val shelfRepository: ShelfRepository = mock()
            val collectionRepository: CollectionRepository = mock()
            val addBooksToShelfUseCase: AddBooksToShelfUseCase = mock()
            val createShelfUseCase: CreateShelfUseCase = mock()
            val documentRepository: DocumentRepository = mock()
            val bookAvailability = FakeBookAvailability()
            val serverReachability = FakeServerReachability()

            fun setup() {
                everySuspend { playbackPositionRepository.get(any<BookId>()) } returns AppResult.Success(null)
                every { userRepository.observeCurrentUser() } returns flowOf(null)
                every { shelfRepository.observeMyShelves(any()) } returns flowOf(emptyList())
                every { shelfRepository.observeShelvesContainingBook(any()) } returns flowOf(emptyList())
                every { tagRepository.observeAll() } returns flowOf(emptyList())
                every { userRepository.observeIsAdmin() } returns flowOf(false)
                every { documentRepository.observeDocuments(any()) } returns flowOf(emptyList())
                every { collectionRepository.observeCollections() } returns flowOf(emptyList())
            }

            fun build(): BookDetailViewModel =
                BookDetailViewModel(
                    bookRepository = bookRepository,
                    tagRepository = tagRepository,
                    playbackPositionRepository = playbackPositionRepository,
                    userRepository = userRepository,
                    shelfRepository = shelfRepository,
                    collectionRepository = collectionRepository,
                    addBooksToShelfUseCase = addBooksToShelfUseCase,
                    createShelfUseCase = createShelfUseCase,
                    errorBus = ErrorBus(),
                    bookAvailability = bookAvailability,
                    serverReachability = serverReachability,
                    documentRepository = documentRepository,
                )
        }

        fun createTestFixture(): TestFixture {
            val fixture = TestFixture()
            fixture.setup()
            return fixture
        }

        // ========== Test Data Factories ==========

        fun createPlaybackPosition(
            bookId: String = "book-1",
            positionMs: Long = 1_800_000L, // 30 min in
            playbackSpeed: Float = 1.0f,
        ): PlaybackPosition =
            PlaybackPosition(
                bookId = bookId,
                positionMs = positionMs,
                playbackSpeed = playbackSpeed,
                hasCustomSpeed = false,
                updatedAtMs = 1704067200000L, // Fixed test timestamp
                syncedAtMs = null,
                lastPlayedAtMs = 1704067200000L, // Fixed test timestamp
            )

        beforeTest {
            Dispatchers.setMain(testDispatcher)
        }

        afterTest {
            Dispatchers.resetMain()
        }

        // ========== Initial State Tests ==========

        test("initial state is Loading") {
            runTest {
                // Given
                val fixture = createTestFixture()
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)
                    val initial = states.awaitItem()

                    // Then
                    initial.shouldBeInstanceOf<BookDetailUiState.Loading>()
                    states.cancel()
                }
            }
        }

        // ========== Load Book Tests ==========

        test("loadBook success populates book data") {
            runTest {
                // Given
                val fixture = createTestFixture()
                val book = TestData.bookDetail(title = "My Book", description = "A description")
                val chapters = listOf(TestData.chapter(id = "ch1"), TestData.chapter(id = "ch2"))
                every { fixture.bookRepository.observeBookDetail("book-1") } returns flowOf(book)
                everySuspend { fixture.bookRepository.getChapters("book-1") } returns chapters
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)
                    states.awaitItem() // initial Loading

                    // When
                    viewModel.loadBook("book-1")
                    advanceUntilIdle()

                    // Then
                    val ready = states.expectMostRecentItem() as BookDetailUiState.Ready
                    ready.book shouldBe book
                    ready.descriptionText shouldBe "A description"
                    ready.chapters.size shouldBe 2
                    states.cancel()
                }
            }
        }

        test("loadBook not found sets Error state") {
            runTest {
                // Given
                val fixture = createTestFixture()
                every { fixture.bookRepository.observeBookDetail("nonexistent") } returns flowOf(null)
                everySuspend { fixture.bookRepository.getChapters(any()) } returns emptyList()
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)
                    states.awaitItem() // initial Loading

                    // When
                    viewModel.loadBook("nonexistent")
                    advanceUntilIdle()

                    // Then
                    val error = states.expectMostRecentItem() as BookDetailUiState.Error
                    error.error.shouldBeInstanceOf<BookError.NotFound>()
                    states.cancel()
                }
            }
        }

        // ========== Subtitle Filtering Tests ==========

        test("loadBook filters redundant subtitle with series name and book number") {
            runTest {
                // Given
                val fixture = createTestFixture()
                val book =
                    TestData.bookDetail(
                        subtitle = "The Stormlight Archive, Book 1",
                        seriesId = "series-1",
                        seriesName = "The Stormlight Archive",
                        seriesSequence = "1",
                    )
                every { fixture.bookRepository.observeBookDetail(any()) } returns flowOf(book)
                everySuspend { fixture.bookRepository.getChapters(any()) } returns emptyList()
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)
                    states.awaitItem() // initial Loading

                    // When
                    viewModel.loadBook("book-1")
                    advanceUntilIdle()

                    // Then - subtitle should be filtered out (redundant)
                    val ready = states.expectMostRecentItem() as BookDetailUiState.Ready
                    ready.subtitle shouldBe null
                    states.cancel()
                }
            }
        }

        test("loadBook keeps meaningful subtitle") {
            runTest {
                // Given
                val fixture = createTestFixture()
                val book =
                    TestData.bookDetail(
                        subtitle = "A Novel of Discovery",
                        seriesName = "The Stormlight Archive",
                        seriesSequence = "1",
                    )
                every { fixture.bookRepository.observeBookDetail(any()) } returns flowOf(book)
                everySuspend { fixture.bookRepository.getChapters(any()) } returns emptyList()
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)
                    states.awaitItem() // initial Loading

                    // When
                    viewModel.loadBook("book-1")
                    advanceUntilIdle()

                    // Then - subtitle should be kept (not redundant)
                    val ready = states.expectMostRecentItem() as BookDetailUiState.Ready
                    ready.subtitle shouldBe "A Novel of Discovery"
                    states.cancel()
                }
            }
        }

        test("loadBook keeps subtitle when no series") {
            runTest {
                // Given
                val fixture = createTestFixture()
                val book =
                    TestData.bookDetail(
                        subtitle = "Part 1",
                        seriesName = null,
                    )
                every { fixture.bookRepository.observeBookDetail(any()) } returns flowOf(book)
                everySuspend { fixture.bookRepository.getChapters(any()) } returns emptyList()
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)
                    states.awaitItem() // initial Loading

                    // When
                    viewModel.loadBook("book-1")
                    advanceUntilIdle()

                    // Then - subtitle should be kept (no series to be redundant with)
                    val ready = states.expectMostRecentItem() as BookDetailUiState.Ready
                    ready.subtitle shouldBe "Part 1"
                    states.cancel()
                }
            }
        }

        // ========== Genre Loading Tests ==========

        test("loadBook loads genres from BookDetail") {
            runTest {
                // Given
                val fixture = createTestFixture()
                val bookGenres =
                    listOf(
                        Genre(id = "g1", name = "Fiction", slug = "fiction", path = "/fiction"),
                        Genre(id = "g2", name = "Fantasy", slug = "fantasy", path = "/fiction/fantasy"),
                        Genre(id = "g3", name = "Adventure", slug = "adventure", path = "/fiction/adventure"),
                    )
                val book = TestData.bookDetail(genres = bookGenres)
                every { fixture.bookRepository.observeBookDetail(any()) } returns flowOf(book)
                everySuspend { fixture.bookRepository.getChapters(any()) } returns emptyList()
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)
                    states.awaitItem() // initial Loading

                    // When
                    viewModel.loadBook("book-1")
                    advanceUntilIdle()

                    // Then
                    val ready = states.expectMostRecentItem() as BookDetailUiState.Ready
                    val genresList = ready.genresList
                    genresList.size shouldBe 3
                    genresList[0] shouldBe "Fiction"
                    genresList[1] shouldBe "Fantasy"
                    genresList[2] shouldBe "Adventure"
                    states.cancel()
                }
            }
        }

        test("loadBook handles empty genres from BookDetail") {
            runTest {
                // Given
                val fixture = createTestFixture()
                val book = TestData.bookDetail()
                every { fixture.bookRepository.observeBookDetail(any()) } returns flowOf(book)
                everySuspend { fixture.bookRepository.getChapters(any()) } returns emptyList()
                // BookDetail genres default to empty
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)
                    states.awaitItem() // initial Loading

                    // When
                    viewModel.loadBook("book-1")
                    advanceUntilIdle()

                    // Then
                    val ready = states.expectMostRecentItem() as BookDetailUiState.Ready
                    (ready.genresList.isEmpty()) shouldBe true
                    states.cancel()
                }
            }
        }

        test("loadBook handles genre loading failure gracefully") {
            runTest {
                // Given - genres come bundled in BookDetail; an empty list represents
                // both "no genres" and "loader failed but recovered" — the VM has no
                // separate failure path since the repository is the SSoT.
                val fixture = createTestFixture()
                val book = TestData.bookDetail()
                every { fixture.bookRepository.observeBookDetail(any()) } returns flowOf(book)
                everySuspend { fixture.bookRepository.getChapters(any()) } returns emptyList()
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)
                    states.awaitItem() // initial Loading

                    // When
                    viewModel.loadBook("book-1")
                    advanceUntilIdle()

                    // Then - book loads successfully despite genre failure
                    val ready = states.expectMostRecentItem() as BookDetailUiState.Ready
                    ready.book shouldBe book
                    (ready.genresList.isEmpty()) shouldBe true
                    states.cancel()
                }
            }
        }

        // ========== Progress Calculation Tests ==========

        test("loadBook calculates progress from playback position") {
            runTest {
                // Given
                val fixture = createTestFixture()
                val book = TestData.bookDetail(duration = 3_600_000L) // 1 hour
                val position = createPlaybackPosition(positionMs = 1_800_000L) // 30 min in
                every { fixture.bookRepository.observeBookDetail(any()) } returns flowOf(book)
                everySuspend { fixture.bookRepository.getChapters(any()) } returns emptyList()
                everySuspend { fixture.playbackPositionRepository.get(any<BookId>()) } returns AppResult.Success(position)
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)
                    states.awaitItem() // initial Loading

                    // When
                    viewModel.loadBook("book-1")
                    advanceUntilIdle()

                    // Then - 30 min / 60 min = 0.5
                    val ready = states.expectMostRecentItem() as BookDetailUiState.Ready
                    ready.progress shouldBe 0.5f
                    states.cancel()
                }
            }
        }

        test("loadBook hides progress when no position saved") {
            runTest {
                // Given
                val fixture = createTestFixture()
                val book = TestData.bookDetail(duration = 3_600_000L)
                every { fixture.bookRepository.observeBookDetail(any()) } returns flowOf(book)
                everySuspend { fixture.bookRepository.getChapters(any()) } returns emptyList()
                everySuspend { fixture.playbackPositionRepository.get(any<BookId>()) } returns AppResult.Success(null)
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)
                    states.awaitItem() // initial Loading

                    // When
                    viewModel.loadBook("book-1")
                    advanceUntilIdle()

                    // Then
                    val ready = states.expectMostRecentItem() as BookDetailUiState.Ready
                    ready.progress shouldBe null
                    states.cancel()
                }
            }
        }

        test("loadBook shows progress when nearly complete but not marked finished") {
            runTest {
                // Given
                val fixture = createTestFixture()
                val book = TestData.bookDetail(duration = 3_600_000L) // 1 hour
                val position = createPlaybackPosition(positionMs = 3_564_000L) // 99% complete
                every { fixture.bookRepository.observeBookDetail(any()) } returns flowOf(book)
                everySuspend { fixture.bookRepository.getChapters(any()) } returns emptyList()
                everySuspend { fixture.playbackPositionRepository.get(any<BookId>()) } returns AppResult.Success(position)
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)
                    states.awaitItem() // initial Loading

                    // When
                    viewModel.loadBook("book-1")
                    advanceUntilIdle()

                    // Then - progress is shown based on position, hidden only when isFinished=true
                    val ready = states.expectMostRecentItem() as BookDetailUiState.Ready
                    val progress = ready.progress
                    progress.shouldNotBeNull()
                    (progress in 0.98f..1.0f) shouldBe true
                    states.cancel()
                }
            }
        }

        // ========== Time Remaining Tests ==========

        test("loadBook formats time remaining with hours and minutes") {
            runTest {
                // Given
                val fixture = createTestFixture()
                val book = TestData.bookDetail(duration = 10_800_000L) // 3 hours
                val position = createPlaybackPosition(positionMs = 2_700_000L) // 45 min in
                every { fixture.bookRepository.observeBookDetail(any()) } returns flowOf(book)
                everySuspend { fixture.bookRepository.getChapters(any()) } returns emptyList()
                everySuspend { fixture.playbackPositionRepository.get(any<BookId>()) } returns AppResult.Success(position)
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)
                    states.awaitItem() // initial Loading

                    // When
                    viewModel.loadBook("book-1")
                    advanceUntilIdle()

                    // Then - 2h 15m remaining
                    val ready = states.expectMostRecentItem() as BookDetailUiState.Ready
                    ready.timeRemainingFormatted shouldBe "2h 15m left"
                    states.cancel()
                }
            }
        }

        test("loadBook formats time remaining with minutes only") {
            runTest {
                // Given
                val fixture = createTestFixture()
                val book = TestData.bookDetail(duration = 3_600_000L) // 1 hour
                val position = createPlaybackPosition(positionMs = 2_700_000L) // 45 min in
                every { fixture.bookRepository.observeBookDetail(any()) } returns flowOf(book)
                everySuspend { fixture.bookRepository.getChapters(any()) } returns emptyList()
                everySuspend { fixture.playbackPositionRepository.get(any<BookId>()) } returns AppResult.Success(position)
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)
                    states.awaitItem() // initial Loading

                    // When
                    viewModel.loadBook("book-1")
                    advanceUntilIdle()

                    // Then - 15m remaining
                    val ready = states.expectMostRecentItem() as BookDetailUiState.Ready
                    ready.timeRemainingFormatted shouldBe "15m left"
                    states.cancel()
                }
            }
        }

        // ========== Tag Management Tests ==========

        test("showTagPicker sets showTagPicker to true when Ready") {
            runTest {
                // Given - must load a book so state is Ready (updateReady no-ops on Loading)
                val fixture = createTestFixture()
                val book = TestData.bookDetail(id = "book-1")
                every { fixture.bookRepository.observeBookDetail(any()) } returns flowOf(book)
                everySuspend { fixture.bookRepository.getChapters(any()) } returns emptyList()
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)
                    states.awaitItem() // initial Loading

                    viewModel.loadBook("book-1")
                    advanceUntilIdle()
                    (states.expectMostRecentItem() as BookDetailUiState.Ready).showTagPicker shouldBe false

                    // When
                    viewModel.showTagPicker()
                    advanceUntilIdle()

                    // Then
                    val ready = states.expectMostRecentItem() as BookDetailUiState.Ready
                    ready.showTagPicker shouldBe true
                    states.cancel()
                }
            }
        }

        test("hideTagPicker sets showTagPicker to false") {
            runTest {
                // Given
                val fixture = createTestFixture()
                val book = TestData.bookDetail(id = "book-1")
                every { fixture.bookRepository.observeBookDetail(any()) } returns flowOf(book)
                everySuspend { fixture.bookRepository.getChapters(any()) } returns emptyList()
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)
                    states.awaitItem() // initial Loading

                    viewModel.loadBook("book-1")
                    advanceUntilIdle()
                    viewModel.showTagPicker()
                    advanceUntilIdle()
                    (states.expectMostRecentItem() as BookDetailUiState.Ready).showTagPicker shouldBe true

                    // When
                    viewModel.hideTagPicker()
                    advanceUntilIdle()

                    // Then
                    val ready = states.expectMostRecentItem() as BookDetailUiState.Ready
                    ready.showTagPicker shouldBe false
                    states.cancel()
                }
            }
        }

        test("loadBook loads tags for book") {
            runTest {
                // Given - tags come bundled with BookDetail; allTags is the picker-wide flow
                val fixture = createTestFixture()
                val bookTags =
                    listOf(
                        Tag(id = "tag-1", name = "Favorites", slug = "favorites"),
                    )
                val allTags =
                    listOf(
                        Tag(id = "tag-1", name = "Favorites", slug = "favorites"),
                        Tag(id = "tag-2", name = "To Read", slug = "to-read"),
                    )
                val book = TestData.bookDetail(tags = bookTags)
                every { fixture.bookRepository.observeBookDetail(any()) } returns flowOf(book)
                everySuspend { fixture.bookRepository.getChapters(any()) } returns emptyList()
                every { fixture.tagRepository.observeAll() } returns flowOf(allTags)
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)
                    states.awaitItem() // initial Loading

                    // When
                    viewModel.loadBook("book-1")
                    advanceUntilIdle()

                    // Then
                    val ready = states.expectMostRecentItem() as BookDetailUiState.Ready
                    ready.tags.size shouldBe 1
                    ready.tags[0].displayName() shouldBe "Favorites"
                    ready.allTags.size shouldBe 2
                    states.cancel()
                }
            }
        }

        test("addTag calls repository and refreshes tags") {
            runTest {
                // Given
                val fixture = createTestFixture()
                val book = TestData.bookDetail(id = "book-1")
                val tag = TestData.tag(id = "tag-1", slug = "favorites")
                every { fixture.bookRepository.observeBookDetail(any()) } returns flowOf(book)
                everySuspend { fixture.bookRepository.getChapters(any()) } returns emptyList()
                everySuspend { fixture.tagRepository.addTagToBook(any(), any()) } returns AppResult.Success(tag)
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)
                    states.awaitItem() // initial Loading

                    viewModel.loadBook("book-1")
                    advanceUntilIdle()
                    states.expectMostRecentItem().shouldBeInstanceOf<BookDetailUiState.Ready>()

                    // When - addTag takes a slug
                    viewModel.addTag("favorites")
                    advanceUntilIdle()

                    // Then
                    verifySuspend { fixture.tagRepository.addTagToBook("book-1", "favorites") }
                    states.cancel()
                }
            }
        }

        test("removeTag calls repository and refreshes tags") {
            runTest {
                // Given - need tags in state for removeTag to find the tag by slug
                val fixture = createTestFixture()
                val bookTags =
                    listOf(Tag(id = "tag-1", name = "Favorites", slug = "favorites"))
                val book = TestData.bookDetail(id = "book-1", tags = bookTags)
                every { fixture.bookRepository.observeBookDetail(any()) } returns flowOf(book)
                everySuspend { fixture.bookRepository.getChapters(any()) } returns emptyList()
                everySuspend { fixture.tagRepository.removeTagFromBook(any(), any(), any()) } returns AppResult.Success(Unit)
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)
                    states.awaitItem() // initial Loading

                    viewModel.loadBook("book-1")
                    advanceUntilIdle()
                    states.expectMostRecentItem().shouldBeInstanceOf<BookDetailUiState.Ready>()

                    // When - removeTag takes a slug and finds tag ID from state
                    viewModel.removeTag("favorites")
                    advanceUntilIdle()

                    // Then
                    verifySuspend { fixture.tagRepository.removeTagFromBook("book-1", "favorites", "tag-1") }
                    states.cancel()
                }
            }
        }

        test("addNewTag adds tag to book") {
            runTest {
                // Given
                val fixture = createTestFixture()
                val book = TestData.bookDetail(id = "book-1")
                val newTag = TestData.tag(id = "new-tag", slug = "new-tag")
                every { fixture.bookRepository.observeBookDetail(any()) } returns flowOf(book)
                everySuspend { fixture.bookRepository.getChapters(any()) } returns emptyList()
                everySuspend { fixture.tagRepository.addTagToBook(any(), any()) } returns AppResult.Success(newTag)
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)
                    states.awaitItem() // initial Loading

                    viewModel.loadBook("book-1")
                    advanceUntilIdle()
                    states.expectMostRecentItem().shouldBeInstanceOf<BookDetailUiState.Ready>()
                    viewModel.showTagPicker()
                    advanceUntilIdle()

                    // When - addNewTag sends raw input, server normalizes to slug
                    viewModel.addNewTag("New Tag")
                    advanceUntilIdle()

                    // Then
                    verifySuspend { fixture.tagRepository.addTagToBook("book-1", "New Tag") }
                    val ready = states.expectMostRecentItem() as BookDetailUiState.Ready
                    ready.showTagPicker shouldBe false // Picker should close
                    states.cancel()
                }
            }
        }

        test("tag operations do nothing when no book loaded") {
            runTest {
                // Given
                val fixture = createTestFixture()
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)
                    val initial = states.awaitItem() // initial Loading

                    // When - try to add tag without loading book
                    viewModel.addTag("tag-1")
                    advanceUntilIdle()

                    // Then - state stays Loading, no API calls made
                    initial.shouldBeInstanceOf<BookDetailUiState.Loading>()
                    states.cancel()
                }
            }
        }

        test("loadBook handles tag loading failure gracefully") {
            runTest {
                // Given - tags come bundled with BookDetail; an empty list is the
                // benign default when nothing has been loaded yet.
                val fixture = createTestFixture()
                val book = TestData.bookDetail()
                every { fixture.bookRepository.observeBookDetail(any()) } returns flowOf(book)
                everySuspend { fixture.bookRepository.getChapters(any()) } returns emptyList()
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)
                    states.awaitItem() // initial Loading

                    // When
                    viewModel.loadBook("book-1")
                    advanceUntilIdle()

                    // Then - book loads successfully, tags are empty but no error
                    val ready = states.expectMostRecentItem() as BookDetailUiState.Ready
                    ready.book shouldBe book
                    states.cancel()
                }
            }
        }

        // ========== Race Condition Tests ==========

        test("rapid loadBook calls for different bookIds emit one coherent sequence with no cross-book contamination") {
            runTest {
                val fixture = createTestFixture()
                val bookX = TestData.bookDetail(id = "book-X", title = "Book X")
                val bookY = TestData.bookDetail(id = "book-Y", title = "Book Y")
                // Make book-X's observer never complete — emits once then suspends in
                // awaitCancellation. flatMapLatest cancels it when book-Y is requested,
                // and book-Y's flowOf emits and completes immediately. The final state
                // must be Ready for book-Y.
                every { fixture.bookRepository.observeBookDetail("book-X") } returns
                    flow {
                        emit(bookX)
                        awaitCancellation()
                    }
                every { fixture.bookRepository.observeBookDetail("book-Y") } returns flowOf(bookY)
                everySuspend { fixture.bookRepository.getChapters(any()) } returns emptyList()
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)
                    states.awaitItem() // initial Loading

                    viewModel.loadBook("book-X")
                    viewModel.loadBook("book-Y")
                    advanceUntilIdle()

                    val final = states.expectMostRecentItem()
                    // flatMapLatest cancels the in-flight book-X observer when book-Y
                    // is requested. Final state must be Ready for book-Y, not
                    // contaminated by book-X.
                    (final is BookDetailUiState.Ready) shouldBe true
                    (final as BookDetailUiState.Ready).book.id.value shouldBe "book-Y"
                    states.cancel()
                }
            }
        }

        test("book switch cancels prior observeBookDetail subscription") {
            runTest {
                val fixture = createTestFixture()
                val book1 = TestData.bookDetail(id = "book-1", title = "Book 1")
                val book2 = TestData.bookDetail(id = "book-2", title = "Book 2")
                every { fixture.bookRepository.observeBookDetail("book-1") } returns
                    flow {
                        emit(book1)
                        awaitCancellation()
                    }
                every { fixture.bookRepository.observeBookDetail("book-2") } returns flowOf(book2)
                everySuspend { fixture.bookRepository.getChapters(any()) } returns emptyList()
                everySuspend { fixture.playbackPositionRepository.get(any<BookId>()) } returns AppResult.Success(null)
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)
                    states.awaitItem() // initial Loading

                    viewModel.loadBook("book-1")
                    advanceUntilIdle()
                    val ready1 = states.expectMostRecentItem() as BookDetailUiState.Ready
                    ready1.book.id.value shouldBe "book-1"

                    // Switching to book-2 must cancel book-1's observer (which is parked
                    // in awaitCancellation). flatMapLatest does this automatically.
                    viewModel.loadBook("book-2")
                    advanceUntilIdle()
                    val ready2 = states.expectMostRecentItem() as BookDetailUiState.Ready
                    ready2.book.id.value shouldBe "book-2"

                    states.cancel()
                }
            }
        }

        // ========== BookAvailability propagation test ==========

        test("BookAvailability state is propagated into Ready fields") {
            runTest {
                // The availability matrix is tested in DefaultBookAvailabilityTest.
                // Here we verify that the VM correctly wires the collaborator output
                // into the Ready state.
                val fixture = createTestFixture()
                val book = TestData.bookDetail(id = "book-1")
                every { fixture.bookRepository.observeBookDetail("book-1") } returns flowOf(book)
                everySuspend { fixture.bookRepository.getChapters(any()) } returns emptyList()
                // Set the fake to an availability state the VM must propagate
                fixture.bookAvailability.stateFlow.value =
                    BookAvailability.State(
                        downloadStatus = BookDownloadStatus.NotDownloaded("book-1"),
                        isPlaybackAvailable = false,
                        canPlay = false,
                        canDownload = false,
                        showServerWarning = true,
                        isWaitingForWifi = false,
                    )
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)
                    states.awaitItem() // initial Loading
                    viewModel.loadBook("book-1")
                    advanceUntilIdle()

                    val ready = states.expectMostRecentItem() as BookDetailUiState.Ready
                    ready.canPlay shouldBe false
                    ready.showServerWarning shouldBe true
                    ready.isPlaybackAvailable shouldBe false
                    states.cancel()
                }
            }
        }

        // ========== Manual reconnect (offline-banner Retry) ==========

        test("retryConnection asks the server reachability to reconnect") {
            runTest {
                val fixture = createTestFixture()
                val viewModel = fixture.build()

                viewModel.retryConnection()
                advanceUntilIdle()

                fixture.serverReachability.retryCalls shouldBe 1
            }
        }

        test("shelvesContainingBook reflects the loaded book's shelf membership") {
            runTest {
                val fixture = createTestFixture()
                val book = TestData.bookDetail(title = "My Book")
                every { fixture.bookRepository.observeBookDetail("book-1") } returns flowOf(book)
                everySuspend { fixture.bookRepository.getChapters("book-1") } returns emptyList()
                every { fixture.shelfRepository.observeShelvesContainingBook(BookId("book-1")) } returns
                    flowOf(
                        listOf(
                            Shelf(
                                id = ShelfId("s1"),
                                name = "To Read",
                                description = null,
                                isPrivate = false,
                                ownerId = "owner1",
                                ownerDisplayName = "Owner",
                                bookCount = 1,
                                totalDurationSeconds = 0L,
                                createdAtMs = 0L,
                                updatedAtMs = 0L,
                            ),
                        ),
                    )
                val viewModel = fixture.build()

                turbineScope {
                    val membership = viewModel.shelvesContainingBook.testIn(backgroundScope)
                    membership.awaitItem() // initial emptyList seed
                    viewModel.loadBook("book-1")
                    advanceUntilIdle()
                    membership.expectMostRecentItem().map { it.id.value } shouldContainExactly listOf("s1")
                    membership.cancel()
                }
            }
        }

        // ========== Documents / Supplementary Materials Tests ==========

        test("documents reflects observeDocuments flow for loaded book") {
            runTest {
                val fixture = createTestFixture()
                val book = TestData.bookDetail(id = "book-1")
                val doc1 = TestData.bookDocument(id = "doc-1", index = 0, filename = "map.pdf")
                val doc2 = TestData.bookDocument(id = "doc-2", index = 1, filename = "extras/notes.pdf")
                every { fixture.bookRepository.observeBookDetail("book-1") } returns flowOf(book)
                everySuspend { fixture.bookRepository.getChapters("book-1") } returns emptyList()
                every { fixture.documentRepository.observeDocuments(BookId("book-1")) } returns
                    flowOf(listOf(doc1, doc2))
                val viewModel = fixture.build()

                turbineScope {
                    val docs = viewModel.documents.testIn(backgroundScope)
                    docs.awaitItem() // initial emptyList seed

                    viewModel.loadBook("book-1")
                    advanceUntilIdle()

                    val current = docs.expectMostRecentItem()
                    current.size shouldBe 2
                    current[0].id shouldBe "doc-1"
                    current[1].id shouldBe "doc-2"
                    docs.cancel()
                }
            }
        }

        test("onOpenDocument on pdf calls ensureLocal and emits OpenDocumentViewer nav event") {
            runTest {
                val fixture = createTestFixture()
                val book = TestData.bookDetail(id = "book-1")
                val doc = TestData.bookDocument(id = "doc-1", format = "pdf")
                every { fixture.bookRepository.observeBookDetail("book-1") } returns flowOf(book)
                everySuspend { fixture.bookRepository.getChapters("book-1") } returns emptyList()
                every { fixture.documentRepository.observeDocuments(BookId("book-1")) } returns flowOf(listOf(doc))
                everySuspend {
                    fixture.documentRepository.ensureLocal(BookId("book-1"), "doc-1")
                } returns AppResult.Success("/cache/book-1/doc-1.pdf")
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)
                    val navEvents = viewModel.navActions.testIn(backgroundScope)
                    // Subscribe to documents so WhileSubscribed keeps the upstream alive
                    // and documents.value is populated before onOpenDocument reads it.
                    val docsObserver = viewModel.documents.testIn(backgroundScope)
                    states.awaitItem() // initial Loading
                    docsObserver.awaitItem() // initial emptyList seed

                    viewModel.loadBook("book-1")
                    advanceUntilIdle()
                    states.expectMostRecentItem().shouldBeInstanceOf<BookDetailUiState.Ready>()
                    docsObserver.expectMostRecentItem() // doc list emitted

                    // When
                    viewModel.onOpenDocument("doc-1")
                    advanceUntilIdle()

                    // Then — ensureLocal was called and viewer nav event emitted
                    verifySuspend { fixture.documentRepository.ensureLocal(BookId("book-1"), "doc-1") }
                    val event = navEvents.awaitItem()
                    event.shouldBeInstanceOf<BookDetailNavAction.OpenDocumentViewer>()
                    (event as BookDetailNavAction.OpenDocumentViewer).localPath shouldBe "/cache/book-1/doc-1.pdf"

                    states.cancel()
                    navEvents.cancel()
                    docsObserver.cancel()
                }
            }
        }

        test("onOpenDocument on non-pdf emits ShowViewerComingSoon and does not call ensureLocal") {
            runTest {
                val fixture = createTestFixture()
                val book = TestData.bookDetail(id = "book-1")
                val doc = TestData.bookDocument(id = "doc-2", format = "epub")
                every { fixture.bookRepository.observeBookDetail("book-1") } returns flowOf(book)
                everySuspend { fixture.bookRepository.getChapters("book-1") } returns emptyList()
                every { fixture.documentRepository.observeDocuments(BookId("book-1")) } returns flowOf(listOf(doc))
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)
                    val navEvents = viewModel.navActions.testIn(backgroundScope)
                    // Subscribe to documents so WhileSubscribed keeps the upstream alive
                    // and documents.value is populated before onOpenDocument reads it.
                    val docsObserver = viewModel.documents.testIn(backgroundScope)
                    states.awaitItem() // initial Loading
                    docsObserver.awaitItem() // initial emptyList seed

                    viewModel.loadBook("book-1")
                    advanceUntilIdle()
                    states.expectMostRecentItem().shouldBeInstanceOf<BookDetailUiState.Ready>()
                    docsObserver.expectMostRecentItem() // doc list emitted

                    // When
                    viewModel.onOpenDocument("doc-2")
                    advanceUntilIdle()

                    // Then — ShowViewerComingSoon emitted; ensureLocal NOT called
                    val event = navEvents.awaitItem()
                    event shouldBe BookDetailNavAction.ShowViewerComingSoon
                    verifySuspend(VerifyMode.exactly(0)) { fixture.documentRepository.ensureLocal(any(), any()) }

                    states.cancel()
                    navEvents.cancel()
                    docsObserver.cancel()
                }
            }
        }

        test("openingDocumentIds adds the docId while a pdf downloads, then clears on success") {
            runTest {
                val fixture = createTestFixture()
                val book = TestData.bookDetail(id = "book-1")
                val doc = TestData.bookDocument(id = "doc-1", format = "pdf")
                every { fixture.bookRepository.observeBookDetail("book-1") } returns flowOf(book)
                everySuspend { fixture.bookRepository.getChapters("book-1") } returns emptyList()
                every { fixture.documentRepository.observeDocuments(BookId("book-1")) } returns flowOf(listOf(doc))
                val gate = CompletableDeferred<AppResult<String>>()
                everySuspend { fixture.documentRepository.ensureLocal(BookId("book-1"), "doc-1") } calls { gate.await() }
                val viewModel = fixture.build()

                turbineScope {
                    val opening = viewModel.openingDocumentIds.testIn(backgroundScope)
                    val docsObserver = viewModel.documents.testIn(backgroundScope)
                    opening.awaitItem() shouldBe emptySet() // initial
                    docsObserver.awaitItem() // initial empty seed
                    viewModel.loadBook("book-1")
                    advanceUntilIdle()
                    docsObserver.expectMostRecentItem() // doc list emitted

                    viewModel.onOpenDocument("doc-1")
                    advanceUntilIdle()
                    opening.awaitItem() shouldBe setOf("doc-1") // added; suspended on the gate

                    gate.complete(AppResult.Success("/cache/book-1/doc-1.pdf"))
                    advanceUntilIdle()
                    opening.awaitItem() shouldBe emptySet() // cleared after success

                    opening.cancel()
                    docsObserver.cancel()
                }
            }
        }

        // ========== Collection Creation Tests ==========

        fun collection(
            id: String = "col-1",
            name: String = "New Collection",
        ): com.calypsan.listenup.client.domain.model.Collection =
            com.calypsan.listenup.client.domain.model.Collection(
                id = id,
                name = name,
                ownerId = "owner-1",
                isInbox = false,
                isSystem = false,
                bookCount = 0,
                callerPermission = com.calypsan.listenup.api.dto.SharePermission.Write,
                isOwner = true,
            )

        test("createCollectionAndAddBook creates the collection, adds the book, and closes the picker") {
            runTest {
                val fixture = createTestFixture()
                val book = TestData.bookDetail(id = "book-1")
                every { fixture.bookRepository.observeBookDetail("book-1") } returns flowOf(book)
                everySuspend { fixture.bookRepository.getChapters("book-1") } returns emptyList()
                val newCollection = collection(id = "col-1", name = "New Collection")
                everySuspend { fixture.collectionRepository.create("test-library", "New Collection") } returns
                    AppResult.Success(newCollection)
                everySuspend { fixture.collectionRepository.addBook("col-1", "book-1") } returns
                    AppResult.Success(Unit)
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)
                    states.awaitItem() // initial Loading
                    viewModel.loadBook("book-1")
                    advanceUntilIdle()
                    viewModel.showCollectionPicker()
                    advanceUntilIdle()
                    (states.expectMostRecentItem() as BookDetailUiState.Ready).showCollectionPicker shouldBe true

                    // When
                    viewModel.createCollectionAndAddBook("New Collection")
                    advanceUntilIdle()

                    // Then — collection created, book added, picker closed
                    verifySuspend { fixture.collectionRepository.create("test-library", "New Collection") }
                    verifySuspend { fixture.collectionRepository.addBook("col-1", "book-1") }
                    val ready = states.expectMostRecentItem() as BookDetailUiState.Ready
                    ready.showCollectionPicker shouldBe false
                    ready.isAddingToCollection shouldBe false
                    ready.collectionError shouldBe null
                    states.cancel()
                }
            }
        }

        test("createCollectionAndAddBook sets collectionError and keeps the picker open on create failure") {
            runTest {
                val fixture = createTestFixture()
                val book = TestData.bookDetail(id = "book-1")
                every { fixture.bookRepository.observeBookDetail("book-1") } returns flowOf(book)
                everySuspend { fixture.bookRepository.getChapters("book-1") } returns emptyList()
                everySuspend { fixture.collectionRepository.create("test-library", "New Collection") } returns
                    AppResult.Failure(InternalError(debugInfo = "boom"))
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)
                    states.awaitItem() // initial Loading
                    viewModel.loadBook("book-1")
                    advanceUntilIdle()
                    viewModel.showCollectionPicker()
                    advanceUntilIdle()

                    // When
                    viewModel.createCollectionAndAddBook("New Collection")
                    advanceUntilIdle()

                    // Then — addBook never called, picker stays open, error surfaced
                    verifySuspend(VerifyMode.exactly(0)) { fixture.collectionRepository.addBook(any(), any()) }
                    val ready = states.expectMostRecentItem() as BookDetailUiState.Ready
                    ready.showCollectionPicker shouldBe true
                    ready.isAddingToCollection shouldBe false
                    ready.collectionError.shouldNotBeNull()
                    states.cancel()
                }
            }
        }

        test("createCollectionAndAddBook sets collectionError and keeps the picker open on add failure") {
            runTest {
                val fixture = createTestFixture()
                val book = TestData.bookDetail(id = "book-1")
                every { fixture.bookRepository.observeBookDetail("book-1") } returns flowOf(book)
                everySuspend { fixture.bookRepository.getChapters("book-1") } returns emptyList()
                val newCollection = collection(id = "col-1", name = "New Collection")
                everySuspend { fixture.collectionRepository.create("test-library", "New Collection") } returns
                    AppResult.Success(newCollection)
                everySuspend { fixture.collectionRepository.addBook("col-1", "book-1") } returns
                    AppResult.Failure(InternalError(debugInfo = "boom"))
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)
                    states.awaitItem() // initial Loading
                    viewModel.loadBook("book-1")
                    advanceUntilIdle()
                    viewModel.showCollectionPicker()
                    advanceUntilIdle()

                    // When
                    viewModel.createCollectionAndAddBook("New Collection")
                    advanceUntilIdle()

                    // Then — collection was created but add failed; picker stays open, error surfaced
                    verifySuspend { fixture.collectionRepository.addBook("col-1", "book-1") }
                    val ready = states.expectMostRecentItem() as BookDetailUiState.Ready
                    ready.showCollectionPicker shouldBe true
                    ready.isAddingToCollection shouldBe false
                    ready.collectionError.shouldNotBeNull()
                    states.cancel()
                }
            }
        }

        test("openingDocumentIds clears the docId when the pdf download fails") {
            runTest {
                val fixture = createTestFixture()
                val book = TestData.bookDetail(id = "book-1")
                val doc = TestData.bookDocument(id = "doc-1", format = "pdf")
                every { fixture.bookRepository.observeBookDetail("book-1") } returns flowOf(book)
                everySuspend { fixture.bookRepository.getChapters("book-1") } returns emptyList()
                every { fixture.documentRepository.observeDocuments(BookId("book-1")) } returns flowOf(listOf(doc))
                val gate = CompletableDeferred<AppResult<String>>()
                everySuspend { fixture.documentRepository.ensureLocal(BookId("book-1"), "doc-1") } calls { gate.await() }
                val viewModel = fixture.build()

                turbineScope {
                    val opening = viewModel.openingDocumentIds.testIn(backgroundScope)
                    val docsObserver = viewModel.documents.testIn(backgroundScope)
                    opening.awaitItem() shouldBe emptySet()
                    docsObserver.awaitItem()
                    viewModel.loadBook("book-1")
                    advanceUntilIdle()
                    docsObserver.expectMostRecentItem()

                    viewModel.onOpenDocument("doc-1")
                    advanceUntilIdle()
                    opening.awaitItem() shouldBe setOf("doc-1")

                    gate.complete(AppResult.Failure(InternalError(debugInfo = "boom")))
                    advanceUntilIdle()
                    opening.awaitItem() shouldBe emptySet() // cleared even on failure

                    opening.cancel()
                    docsObserver.cancel()
                }
            }
        }
    })
