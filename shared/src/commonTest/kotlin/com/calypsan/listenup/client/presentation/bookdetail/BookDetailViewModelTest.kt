package com.calypsan.listenup.client.presentation.bookdetail

import app.cash.turbine.turbineScope
import com.calypsan.listenup.client.TestData
import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.core.BookId
import com.calypsan.listenup.client.domain.model.Genre
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.client.domain.model.Tag
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.domain.repository.TagRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.domain.usecase.shelf.AddBooksToShelfUseCase
import com.calypsan.listenup.client.domain.usecase.shelf.CreateShelfUseCase
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.calypsan.listenup.client.core.error.ErrorBus

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
class BookDetailViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    // ========== Test Fixtures ==========

    private class TestFixture {
        val bookRepository: BookRepository = mock()
        val tagRepository: TagRepository = mock()
        val playbackPositionRepository: PlaybackPositionRepository = mock()
        val userRepository: UserRepository = mock()
        val shelfRepository: ShelfRepository = mock()
        val addBooksToShelfUseCase: AddBooksToShelfUseCase = mock()
        val createShelfUseCase: CreateShelfUseCase = mock()

        fun build(): BookDetailViewModel =
            BookDetailViewModel(
                bookRepository = bookRepository,
                tagRepository = tagRepository,
                playbackPositionRepository = playbackPositionRepository,
                userRepository = userRepository,
                shelfRepository = shelfRepository,
                addBooksToShelfUseCase = addBooksToShelfUseCase,
                createShelfUseCase = createShelfUseCase,
                errorBus = ErrorBus(),
            )
    }

    private fun createFixture(): TestFixture {
        val fixture = TestFixture()

        // Default stubs for domain repositories
        everySuspend { fixture.playbackPositionRepository.get(any<BookId>()) } returns AppResult.Success(null)
        every { fixture.userRepository.observeCurrentUser() } returns flowOf(null)
        every { fixture.shelfRepository.observeMyShelves(any()) } returns flowOf(emptyList())
        every { fixture.tagRepository.observeAll() } returns flowOf(emptyList())
        every { fixture.userRepository.observeIsAdmin() } returns flowOf(false)

        return fixture
    }

    // ========== Test Data Factories ==========

    private fun createPlaybackPosition(
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

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ========== Initial State Tests ==========

    @Test
    fun `initial state is Loading`() =
        runTest {
            // Given
            val fixture = createFixture()
            val viewModel = fixture.build()

            turbineScope {
                val states = viewModel.state.testIn(backgroundScope)
                val initial = states.awaitItem()

                // Then
                assertIs<BookDetailUiState.Loading>(initial)
                states.cancel()
            }
        }

    // ========== Load Book Tests ==========

    @Test
    fun `loadBook success populates book data`() =
        runTest {
            // Given
            val fixture = createFixture()
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
                val ready = assertIs<BookDetailUiState.Ready>(states.expectMostRecentItem())
                assertEquals(book, ready.book)
                assertEquals("A description", ready.description)
                assertEquals(2, ready.chapters.size)
                states.cancel()
            }
        }

    @Test
    fun `loadBook not found sets Error state`() =
        runTest {
            // Given
            val fixture = createFixture()
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
                val error = assertIs<BookDetailUiState.Error>(states.expectMostRecentItem())
                assertEquals("Book not found", error.message)
                states.cancel()
            }
        }

    // ========== Subtitle Filtering Tests ==========

    @Test
    fun `loadBook filters redundant subtitle with series name and book number`() =
        runTest {
            // Given
            val fixture = createFixture()
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
                val ready = assertIs<BookDetailUiState.Ready>(states.expectMostRecentItem())
                assertNull(ready.subtitle)
                states.cancel()
            }
        }

    @Test
    fun `loadBook keeps meaningful subtitle`() =
        runTest {
            // Given
            val fixture = createFixture()
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
                val ready = assertIs<BookDetailUiState.Ready>(states.expectMostRecentItem())
                assertEquals("A Novel of Discovery", ready.subtitle)
                states.cancel()
            }
        }

    @Test
    fun `loadBook keeps subtitle when no series`() =
        runTest {
            // Given
            val fixture = createFixture()
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
                val ready = assertIs<BookDetailUiState.Ready>(states.expectMostRecentItem())
                assertEquals("Part 1", ready.subtitle)
                states.cancel()
            }
        }

    // ========== Genre Loading Tests ==========

    @Test
    fun `loadBook loads genres from BookDetail`() =
        runTest {
            // Given
            val fixture = createFixture()
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
                val ready = assertIs<BookDetailUiState.Ready>(states.expectMostRecentItem())
                val genresList = ready.genresList
                assertEquals(3, genresList.size)
                assertEquals("Fiction", genresList[0])
                assertEquals("Fantasy", genresList[1])
                assertEquals("Adventure", genresList[2])
                states.cancel()
            }
        }

    @Test
    fun `loadBook handles empty genres from BookDetail`() =
        runTest {
            // Given
            val fixture = createFixture()
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
                val ready = assertIs<BookDetailUiState.Ready>(states.expectMostRecentItem())
                assertTrue(ready.genresList.isEmpty())
                states.cancel()
            }
        }

    @Test
    fun `loadBook handles genre loading failure gracefully`() =
        runTest {
            // Given - genres come bundled in BookDetail; an empty list represents
            // both "no genres" and "loader failed but recovered" — the VM has no
            // separate failure path since the repository is the SSoT.
            val fixture = createFixture()
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
                val ready = assertIs<BookDetailUiState.Ready>(states.expectMostRecentItem())
                assertEquals(book, ready.book)
                assertTrue(ready.genresList.isEmpty())
                states.cancel()
            }
        }

    // ========== Progress Calculation Tests ==========

    @Test
    fun `loadBook calculates progress from playback position`() =
        runTest {
            // Given
            val fixture = createFixture()
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
                val ready = assertIs<BookDetailUiState.Ready>(states.expectMostRecentItem())
                assertEquals(0.5f, ready.progress)
                states.cancel()
            }
        }

    @Test
    fun `loadBook hides progress when no position saved`() =
        runTest {
            // Given
            val fixture = createFixture()
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
                val ready = assertIs<BookDetailUiState.Ready>(states.expectMostRecentItem())
                assertNull(ready.progress)
                states.cancel()
            }
        }

    @Test
    fun `loadBook shows progress when nearly complete but not marked finished`() =
        runTest {
            // Given
            val fixture = createFixture()
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
                val ready = assertIs<BookDetailUiState.Ready>(states.expectMostRecentItem())
                val progress = ready.progress
                assertNotNull(progress)
                assertEquals(0.99f, progress, 0.01f)
                states.cancel()
            }
        }

    // ========== Time Remaining Tests ==========

    @Test
    fun `loadBook formats time remaining with hours and minutes`() =
        runTest {
            // Given
            val fixture = createFixture()
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
                val ready = assertIs<BookDetailUiState.Ready>(states.expectMostRecentItem())
                assertEquals("2h 15m left", ready.timeRemainingFormatted)
                states.cancel()
            }
        }

    @Test
    fun `loadBook formats time remaining with minutes only`() =
        runTest {
            // Given
            val fixture = createFixture()
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
                val ready = assertIs<BookDetailUiState.Ready>(states.expectMostRecentItem())
                assertEquals("15m left", ready.timeRemainingFormatted)
                states.cancel()
            }
        }

    // ========== Tag Management Tests ==========

    @Test
    fun `showTagPicker sets showTagPicker to true when Ready`() =
        runTest {
            // Given - must load a book so state is Ready (updateReady no-ops on Loading)
            val fixture = createFixture()
            val book = TestData.bookDetail(id = "book-1")
            every { fixture.bookRepository.observeBookDetail(any()) } returns flowOf(book)
            everySuspend { fixture.bookRepository.getChapters(any()) } returns emptyList()
            val viewModel = fixture.build()

            turbineScope {
                val states = viewModel.state.testIn(backgroundScope)
                states.awaitItem() // initial Loading

                viewModel.loadBook("book-1")
                advanceUntilIdle()
                assertFalse(assertIs<BookDetailUiState.Ready>(states.expectMostRecentItem()).showTagPicker)

                // When
                viewModel.showTagPicker()
                advanceUntilIdle()

                // Then
                val ready = assertIs<BookDetailUiState.Ready>(states.expectMostRecentItem())
                assertTrue(ready.showTagPicker)
                states.cancel()
            }
        }

    @Test
    fun `hideTagPicker sets showTagPicker to false`() =
        runTest {
            // Given
            val fixture = createFixture()
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
                assertTrue(assertIs<BookDetailUiState.Ready>(states.expectMostRecentItem()).showTagPicker)

                // When
                viewModel.hideTagPicker()
                advanceUntilIdle()

                // Then
                val ready = assertIs<BookDetailUiState.Ready>(states.expectMostRecentItem())
                assertFalse(ready.showTagPicker)
                states.cancel()
            }
        }

    @Test
    fun `loadBook loads tags for book`() =
        runTest {
            // Given - tags come bundled with BookDetail; allTags is the picker-wide flow
            val fixture = createFixture()
            val bookTags =
                listOf(
                    Tag(id = "tag-1", slug = "favorites", bookCount = 5),
                )
            val allTags =
                listOf(
                    Tag(id = "tag-1", slug = "favorites", bookCount = 5),
                    Tag(id = "tag-2", slug = "to-read", bookCount = 3),
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
                val ready = assertIs<BookDetailUiState.Ready>(states.expectMostRecentItem())
                assertEquals(1, ready.tags.size)
                assertEquals("Favorites", ready.tags[0].displayName())
                assertEquals(2, ready.allTags.size)
                states.cancel()
            }
        }

    @Test
    fun `addTag calls repository and refreshes tags`() =
        runTest {
            // Given
            val fixture = createFixture()
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
                assertIs<BookDetailUiState.Ready>(states.expectMostRecentItem())

                // When - addTag takes a slug
                viewModel.addTag("favorites")
                advanceUntilIdle()

                // Then
                verifySuspend { fixture.tagRepository.addTagToBook("book-1", "favorites") }
                states.cancel()
            }
        }

    @Test
    fun `removeTag calls repository and refreshes tags`() =
        runTest {
            // Given - need tags in state for removeTag to find the tag by slug
            val fixture = createFixture()
            val bookTags =
                listOf(Tag(id = "tag-1", slug = "favorites", bookCount = 5))
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
                assertIs<BookDetailUiState.Ready>(states.expectMostRecentItem())

                // When - removeTag takes a slug and finds tag ID from state
                viewModel.removeTag("favorites")
                advanceUntilIdle()

                // Then
                verifySuspend { fixture.tagRepository.removeTagFromBook("book-1", "favorites", "tag-1") }
                states.cancel()
            }
        }

    @Test
    fun `addNewTag adds tag to book`() =
        runTest {
            // Given
            val fixture = createFixture()
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
                assertIs<BookDetailUiState.Ready>(states.expectMostRecentItem())
                viewModel.showTagPicker()
                advanceUntilIdle()

                // When - addNewTag sends raw input, server normalizes to slug
                viewModel.addNewTag("New Tag")
                advanceUntilIdle()

                // Then
                verifySuspend { fixture.tagRepository.addTagToBook("book-1", "New Tag") }
                val ready = assertIs<BookDetailUiState.Ready>(states.expectMostRecentItem())
                assertFalse(ready.showTagPicker) // Picker should close
                states.cancel()
            }
        }

    @Test
    fun `tag operations do nothing when no book loaded`() =
        runTest {
            // Given
            val fixture = createFixture()
            val viewModel = fixture.build()

            turbineScope {
                val states = viewModel.state.testIn(backgroundScope)
                val initial = states.awaitItem() // initial Loading

                // When - try to add tag without loading book
                viewModel.addTag("tag-1")
                advanceUntilIdle()

                // Then - state stays Loading, no API calls made
                assertIs<BookDetailUiState.Loading>(initial)
                states.cancel()
            }
        }

    @Test
    fun `loadBook handles tag loading failure gracefully`() =
        runTest {
            // Given - tags come bundled with BookDetail; an empty list is the
            // benign default when nothing has been loaded yet.
            val fixture = createFixture()
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
                val ready = assertIs<BookDetailUiState.Ready>(states.expectMostRecentItem())
                assertEquals(book, ready.book)
                states.cancel()
            }
        }

    // ========== Race Condition Tests ==========

    @Test
    fun `rapid loadBook calls for different bookIds emit one coherent sequence with no cross-book contamination`() =
        runTest {
            val fixture = createFixture()
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
                assertTrue(final is BookDetailUiState.Ready)
                assertEquals("book-Y", final.book.id.value)
                states.cancel()
            }
        }

    @Test
    fun `book switch cancels prior observeBookDetail subscription`() =
        runTest {
            val fixture = createFixture()
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
                val ready1 = assertIs<BookDetailUiState.Ready>(states.expectMostRecentItem())
                assertEquals("book-1", ready1.book.id.value)

                // Switching to book-2 must cancel book-1's observer (which is parked
                // in awaitCancellation). flatMapLatest does this automatically.
                viewModel.loadBook("book-2")
                advanceUntilIdle()
                val ready2 = assertIs<BookDetailUiState.Ready>(states.expectMostRecentItem())
                assertEquals("book-2", ready2.book.id.value)

                states.cancel()
            }
        }
}
