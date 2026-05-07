package com.calypsan.listenup.client.presentation.admin

import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.domain.model.AdminEvent
import com.calypsan.listenup.client.domain.model.InboxBook
import com.calypsan.listenup.client.domain.model.InboxReleaseResult
import com.calypsan.listenup.client.domain.model.StagedCollection
import com.calypsan.listenup.client.domain.repository.EventStreamRepository
import com.calypsan.listenup.client.domain.usecase.admin.LoadInboxBooksUseCase
import com.calypsan.listenup.client.domain.usecase.admin.ReleaseBooksUseCase
import com.calypsan.listenup.client.domain.usecase.admin.StageCollectionUseCase
import com.calypsan.listenup.client.domain.usecase.admin.UnstageCollectionUseCase
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AdminInboxViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private fun createInboxBook(
        id: String,
        title: String = "Test Book",
        author: String? = "Test Author",
        stagedCollectionIds: List<String> = emptyList(),
    ) = InboxBook(
        id = id,
        title = title,
        author = author,
        coverUrl = null,
        duration = 3600000, // 1 hour
        stagedCollectionIds = stagedCollectionIds,
        stagedCollections = stagedCollectionIds.map { StagedCollection(it, "Collection $it") },
        scannedAt = "2024-01-01T00:00:00Z",
    )

    private class TestFixture {
        val loadInboxBooksUseCase: LoadInboxBooksUseCase = mock()
        val releaseBooksUseCase: ReleaseBooksUseCase = mock()
        val stageCollectionUseCase: StageCollectionUseCase = mock()
        val unstageCollectionUseCase: UnstageCollectionUseCase = mock()
        val eventStreamRepository: EventStreamRepository = mock()
        val adminEvents = MutableSharedFlow<AdminEvent>(extraBufferCapacity = 1)

        init {
            every { eventStreamRepository.adminEvents } returns adminEvents
        }

        fun build() =
            AdminInboxViewModel(
                loadInboxBooksUseCase = loadInboxBooksUseCase,
                releaseBooksUseCase = releaseBooksUseCase,
                stageCollectionUseCase = stageCollectionUseCase,
                unstageCollectionUseCase = unstageCollectionUseCase,
                eventStreamRepository = eventStreamRepository,
            )
    }

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is Loading`() =
        runTest {
            val fixture = TestFixture()
            everySuspend { fixture.loadInboxBooksUseCase() } returns Success(emptyList())

            val viewModel = fixture.build()

            assertIs<AdminInboxUiState.Loading>(viewModel.state.value)
        }

    @Test
    fun `loadInboxBooks transitions to Ready with books`() =
        runTest {
            val books =
                listOf(
                    createInboxBook("book-1", "Book One"),
                    createInboxBook("book-2", "Book Two"),
                )
            val fixture = TestFixture()
            everySuspend { fixture.loadInboxBooksUseCase() } returns Success(books)

            val viewModel = fixture.build()
            advanceUntilIdle()

            val ready = assertIs<AdminInboxUiState.Ready>(viewModel.state.value)
            assertEquals(2, ready.books.size)
            assertEquals("book-1", ready.books[0].id)
            assertEquals("book-2", ready.books[1].id)
        }

    @Test
    fun `loadInboxBooks initial failure transitions to Error`() =
        runTest {
            val fixture = TestFixture()
            // Body-level message convention: pass a typed AppError so the
            // user-facing message survives delegation to the ViewModel.
            everySuspend { fixture.loadInboxBooksUseCase() } returns
                Failure(com.calypsan.listenup.api.error.ValidationError(message = "Network error"))

            val viewModel = fixture.build()
            advanceUntilIdle()

            val error = assertIs<AdminInboxUiState.Error>(viewModel.state.value)
            assertTrue(error.message.contains("Network error"))
        }

    @Test
    fun `toggleBookSelection adds book to selection`() =
        runTest {
            val books = listOf(createInboxBook("book-1"))
            val fixture = TestFixture()
            everySuspend { fixture.loadInboxBooksUseCase() } returns Success(books)

            val viewModel = fixture.build()
            advanceUntilIdle()

            viewModel.toggleBookSelection("book-1")

            val ready = assertIs<AdminInboxUiState.Ready>(viewModel.state.value)
            assertTrue(ready.selectedBookIds.contains("book-1"))
        }

    @Test
    fun `toggleBookSelection removes book from selection if already selected`() =
        runTest {
            val books = listOf(createInboxBook("book-1"))
            val fixture = TestFixture()
            everySuspend { fixture.loadInboxBooksUseCase() } returns Success(books)

            val viewModel = fixture.build()
            advanceUntilIdle()

            viewModel.toggleBookSelection("book-1")
            val afterFirstToggle = assertIs<AdminInboxUiState.Ready>(viewModel.state.value)
            assertTrue(afterFirstToggle.selectedBookIds.contains("book-1"))

            viewModel.toggleBookSelection("book-1")
            val afterSecondToggle = assertIs<AdminInboxUiState.Ready>(viewModel.state.value)
            assertFalse(afterSecondToggle.selectedBookIds.contains("book-1"))
        }

    @Test
    fun `selectAll selects all books`() =
        runTest {
            val books =
                listOf(
                    createInboxBook("book-1"),
                    createInboxBook("book-2"),
                    createInboxBook("book-3"),
                )
            val fixture = TestFixture()
            everySuspend { fixture.loadInboxBooksUseCase() } returns Success(books)

            val viewModel = fixture.build()
            advanceUntilIdle()

            viewModel.selectAll()

            val ready = assertIs<AdminInboxUiState.Ready>(viewModel.state.value)
            assertEquals(3, ready.selectedBookIds.size)
            assertTrue(ready.allSelected)
        }

    @Test
    fun `clearSelection clears all selections`() =
        runTest {
            val books = listOf(createInboxBook("book-1"), createInboxBook("book-2"))
            val fixture = TestFixture()
            everySuspend { fixture.loadInboxBooksUseCase() } returns Success(books)

            val viewModel = fixture.build()
            advanceUntilIdle()

            viewModel.selectAll()
            val afterSelectAll = assertIs<AdminInboxUiState.Ready>(viewModel.state.value)
            assertEquals(2, afterSelectAll.selectedBookIds.size)

            viewModel.clearSelection()
            val afterClear = assertIs<AdminInboxUiState.Ready>(viewModel.state.value)
            assertTrue(afterClear.selectedBookIds.isEmpty())
        }

    @Test
    fun `releaseBooks calls use case and removes books from local state`() =
        runTest {
            val books = listOf(createInboxBook("book-1"), createInboxBook("book-2"))
            val fixture = TestFixture()
            everySuspend { fixture.loadInboxBooksUseCase() } returns Success(books)
            everySuspend { fixture.releaseBooksUseCase(listOf("book-1")) } returns
                Success(
                    InboxReleaseResult(
                        released = 1,
                        publicCount = 1,
                        toCollections = 0,
                    ),
                )

            val viewModel = fixture.build()
            advanceUntilIdle()

            viewModel.releaseBooks(listOf("book-1"))
            advanceUntilIdle()

            val ready = assertIs<AdminInboxUiState.Ready>(viewModel.state.value)
            assertEquals(1, ready.books.size)
            assertEquals("book-2", ready.books[0].id)
            assertFalse(ready.isReleasing)
            assertEquals(1, ready.lastReleaseResult?.released)
        }

    @Test
    fun `releaseBooks clears selection after success`() =
        runTest {
            val books = listOf(createInboxBook("book-1"), createInboxBook("book-2"))
            val fixture = TestFixture()
            everySuspend { fixture.loadInboxBooksUseCase() } returns Success(books)
            everySuspend { fixture.releaseBooksUseCase(listOf("book-1")) } returns
                Success(
                    InboxReleaseResult(
                        released = 1,
                        publicCount = 1,
                        toCollections = 0,
                    ),
                )

            val viewModel = fixture.build()
            advanceUntilIdle()

            viewModel.toggleBookSelection("book-1")
            val afterToggle = assertIs<AdminInboxUiState.Ready>(viewModel.state.value)
            assertEquals(1, afterToggle.selectedBookIds.size)

            viewModel.releaseBooks(listOf("book-1"))
            advanceUntilIdle()

            val ready = assertIs<AdminInboxUiState.Ready>(viewModel.state.value)
            assertTrue(ready.selectedBookIds.isEmpty())
        }

    @Test
    fun `releaseBooks handles error`() =
        runTest {
            val books = listOf(createInboxBook("book-1"))
            val fixture = TestFixture()
            everySuspend { fixture.loadInboxBooksUseCase() } returns Success(books)
            everySuspend { fixture.releaseBooksUseCase(listOf("book-1")) } returns
                Failure(RuntimeException("Failed"))

            val viewModel = fixture.build()
            advanceUntilIdle()

            viewModel.releaseBooks(listOf("book-1"))
            advanceUntilIdle()

            val ready = assertIs<AdminInboxUiState.Ready>(viewModel.state.value)
            assertFalse(ready.isReleasing)
            assertTrue(ready.error != null)
            // Book should still be in the list
            assertEquals(1, ready.books.size)
        }

    @Test
    fun `stageCollection reloads books after success`() =
        runTest {
            val books = listOf(createInboxBook("book-1"))
            val fixture = TestFixture()
            everySuspend { fixture.loadInboxBooksUseCase() } returns Success(books)
            everySuspend { fixture.stageCollectionUseCase("book-1", "coll-1") } returns Success(Unit)

            val viewModel = fixture.build()
            advanceUntilIdle()

            viewModel.stageCollection("book-1", "coll-1")
            advanceUntilIdle()

            // Verify use case was called
            verifySuspend(VerifyMode.atLeast(1)) { fixture.stageCollectionUseCase("book-1", "coll-1") }
            // Verify books were reloaded (called at least twice - init + after stage)
            verifySuspend(VerifyMode.atLeast(2)) { fixture.loadInboxBooksUseCase() }
        }

    @Test
    fun `unstageCollection reloads books after success`() =
        runTest {
            val books = listOf(createInboxBook("book-1", stagedCollectionIds = listOf("coll-1")))
            val fixture = TestFixture()
            everySuspend { fixture.loadInboxBooksUseCase() } returns Success(books)
            everySuspend { fixture.unstageCollectionUseCase("book-1", "coll-1") } returns Success(Unit)

            val viewModel = fixture.build()
            advanceUntilIdle()

            viewModel.unstageCollection("book-1", "coll-1")
            advanceUntilIdle()

            verifySuspend(VerifyMode.atLeast(1)) { fixture.unstageCollectionUseCase("book-1", "coll-1") }
            verifySuspend(VerifyMode.atLeast(2)) { fixture.loadInboxBooksUseCase() }
        }

    @Test
    fun `hasSelectedBooksWithoutCollections returns true when book has no staged collections`() =
        runTest {
            val books =
                listOf(
                    createInboxBook("book-1", stagedCollectionIds = emptyList()),
                )
            val fixture = TestFixture()
            everySuspend { fixture.loadInboxBooksUseCase() } returns Success(books)

            val viewModel = fixture.build()
            advanceUntilIdle()

            viewModel.toggleBookSelection("book-1")

            assertTrue(viewModel.hasSelectedBooksWithoutCollections())
        }

    @Test
    fun `hasSelectedBooksWithoutCollections returns false when all books have collections`() =
        runTest {
            val books =
                listOf(
                    createInboxBook("book-1", stagedCollectionIds = listOf("coll-1")),
                )
            val fixture = TestFixture()
            everySuspend { fixture.loadInboxBooksUseCase() } returns Success(books)

            val viewModel = fixture.build()
            advanceUntilIdle()

            viewModel.toggleBookSelection("book-1")

            assertFalse(viewModel.hasSelectedBooksWithoutCollections())
        }

    @Test
    fun `SSE InboxBookReleased event removes book from list`() =
        runTest {
            val books = listOf(createInboxBook("book-1"), createInboxBook("book-2"))
            val fixture = TestFixture()
            everySuspend { fixture.loadInboxBooksUseCase() } returns Success(books)

            val viewModel = fixture.build()
            advanceUntilIdle()

            val afterLoad = assertIs<AdminInboxUiState.Ready>(viewModel.state.value)
            assertEquals(2, afterLoad.books.size)

            // Emit SSE event
            fixture.adminEvents.emit(AdminEvent.InboxBookReleased("book-1"))
            advanceUntilIdle()

            val afterEvent = assertIs<AdminInboxUiState.Ready>(viewModel.state.value)
            assertEquals(1, afterEvent.books.size)
            assertEquals("book-2", afterEvent.books[0].id)
        }

    @Test
    fun `SSE InboxBookAdded event reloads books`() =
        runTest {
            val books = listOf(createInboxBook("book-1"))
            val fixture = TestFixture()
            everySuspend { fixture.loadInboxBooksUseCase() } returns Success(books)

            val viewModel = fixture.build()
            advanceUntilIdle()

            // Initial load done
            val afterLoad = assertIs<AdminInboxUiState.Ready>(viewModel.state.value)
            assertEquals(1, afterLoad.books.size)

            // Emit SSE event for new book
            fixture.adminEvents.emit(AdminEvent.InboxBookAdded(bookId = "book-2", title = "New Book"))
            advanceUntilIdle()

            // The reload should have been triggered - verify by checking use case was called twice
            verifySuspend(VerifyMode.atLeast(2)) { fixture.loadInboxBooksUseCase() }
        }

    @Test
    fun `clearError clears error state`() =
        runTest {
            // Use a refresh-after-success failure path to exercise Ready.error rather
            // than the terminal Error state: initial load succeeds, then a release
            // failure surfaces a transient snackbar error that clearError resets.
            val books = listOf(createInboxBook("book-1"))
            val fixture = TestFixture()
            everySuspend { fixture.loadInboxBooksUseCase() } returns Success(books)
            everySuspend { fixture.releaseBooksUseCase(listOf("book-1")) } returns
                Failure(RuntimeException("Error"))

            val viewModel = fixture.build()
            advanceUntilIdle()

            viewModel.releaseBooks(listOf("book-1"))
            advanceUntilIdle()

            val withError = assertIs<AdminInboxUiState.Ready>(viewModel.state.value)
            assertTrue(withError.error != null)

            viewModel.clearError()

            val cleared = assertIs<AdminInboxUiState.Ready>(viewModel.state.value)
            assertNull(cleared.error)
        }

    @Test
    fun `clearReleaseResult clears last release result`() =
        runTest {
            val books = listOf(createInboxBook("book-1"))
            val fixture = TestFixture()
            everySuspend { fixture.loadInboxBooksUseCase() } returns Success(books)
            everySuspend { fixture.releaseBooksUseCase(listOf("book-1")) } returns
                Success(
                    InboxReleaseResult(
                        released = 1,
                        publicCount = 1,
                        toCollections = 0,
                    ),
                )

            val viewModel = fixture.build()
            advanceUntilIdle()

            viewModel.releaseBooks(listOf("book-1"))
            advanceUntilIdle()

            val withResult = assertIs<AdminInboxUiState.Ready>(viewModel.state.value)
            assertTrue(withResult.lastReleaseResult != null)

            viewModel.clearReleaseResult()

            val cleared = assertIs<AdminInboxUiState.Ready>(viewModel.state.value)
            assertNull(cleared.lastReleaseResult)
        }

    @Test
    fun `hasBooks returns true when books exist`() =
        runTest {
            val books = listOf(createInboxBook("book-1"))
            val fixture = TestFixture()
            everySuspend { fixture.loadInboxBooksUseCase() } returns Success(books)

            val viewModel = fixture.build()
            advanceUntilIdle()

            val ready = assertIs<AdminInboxUiState.Ready>(viewModel.state.value)
            assertTrue(ready.hasBooks)
        }

    @Test
    fun `hasBooks returns false when no books`() =
        runTest {
            val fixture = TestFixture()
            everySuspend { fixture.loadInboxBooksUseCase() } returns Success(emptyList())

            val viewModel = fixture.build()
            advanceUntilIdle()

            val ready = assertIs<AdminInboxUiState.Ready>(viewModel.state.value)
            assertFalse(ready.hasBooks)
        }

    @Test
    fun `hasSelection returns true when books are selected`() =
        runTest {
            val books = listOf(createInboxBook("book-1"))
            val fixture = TestFixture()
            everySuspend { fixture.loadInboxBooksUseCase() } returns Success(books)

            val viewModel = fixture.build()
            advanceUntilIdle()

            val beforeToggle = assertIs<AdminInboxUiState.Ready>(viewModel.state.value)
            assertFalse(beforeToggle.hasSelection)

            viewModel.toggleBookSelection("book-1")

            val afterToggle = assertIs<AdminInboxUiState.Ready>(viewModel.state.value)
            assertTrue(afterToggle.hasSelection)
        }
}
