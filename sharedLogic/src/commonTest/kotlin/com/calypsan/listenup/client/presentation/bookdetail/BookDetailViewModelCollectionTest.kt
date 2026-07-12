package com.calypsan.listenup.client.presentation.bookdetail

import app.cash.turbine.turbineScope
import com.calypsan.listenup.client.TestData
import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.error.ErrorBus
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.model.Collection
import com.calypsan.listenup.client.domain.repository.BookAvailability
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import com.calypsan.listenup.client.domain.repository.DocumentRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.domain.repository.Reachability
import com.calypsan.listenup.client.domain.repository.ServerReachability
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import com.calypsan.listenup.client.domain.repository.TagRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.domain.usecase.shelf.AddBooksToShelfUseCase
import com.calypsan.listenup.client.domain.usecase.shelf.CreateShelfUseCase
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Tests for the add-to-collection flow in [BookDetailViewModel].
 *
 * Covers:
 * - [addBookToCollection] dispatches to [CollectionRepository.addBook] and closes the picker on success.
 * - [collections] flow excludes system collections.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookDetailViewModelCollectionTest :
    FunSpec({

        val testDispatcher = StandardTestDispatcher()

        // ========== Test Fixtures ==========

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

        class FakeServerReachability : ServerReachability {
            override val state = MutableStateFlow<Reachability>(Reachability.Unknown)

            override suspend fun retry() {}
        }

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
                    campfiresForBook = { flowOf(emptyList()) },
                )
        }

        fun createTestFixture(): TestFixture {
            val fixture = TestFixture()
            fixture.setup()
            return fixture
        }

        beforeTest {
            Dispatchers.setMain(testDispatcher)
        }

        afterTest {
            Dispatchers.resetMain()
        }

        // ========== Collection Flow Tests ==========

        test("addBookToCollection dispatches to the repository and closes the picker on success") {
            runTest {
                // Arrange
                val fixture = createTestFixture()
                val book = TestData.bookDetail(id = "book-1")
                every { fixture.bookRepository.observeBookDetail("book-1") } returns flowOf(book)
                everySuspend { fixture.bookRepository.getChapters("book-1") } returns emptyList()
                everySuspend { fixture.collectionRepository.addBook("col-1", "book-1") } returns
                    AppResult.Success(Unit)
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)
                    states.awaitItem() // initial Loading

                    viewModel.loadBook("book-1")
                    advanceUntilIdle()

                    // Picker is initially closed
                    (states.expectMostRecentItem() as BookDetailUiState.Ready).showCollectionPicker shouldBe false

                    // When — open then add
                    viewModel.showCollectionPicker()
                    advanceUntilIdle()
                    (states.expectMostRecentItem() as BookDetailUiState.Ready).showCollectionPicker shouldBe true

                    viewModel.addBookToCollection("col-1")
                    advanceUntilIdle()

                    // Then — repository called, picker closed
                    verifySuspend { fixture.collectionRepository.addBook("col-1", "book-1") }
                    val ready = states.expectMostRecentItem() as BookDetailUiState.Ready
                    ready.showCollectionPicker shouldBe false
                    ready.isAddingToCollection shouldBe false

                    states.cancel()
                }
            }
        }

        test("collections flow excludes system collections") {
            runTest {
                // Arrange — one system collection, one normal collection
                val fixture = createTestFixture()
                val systemCollection =
                    Collection(
                        id = "sys-1",
                        name = "All Books",
                        ownerId = "system",
                        isInbox = false,
                        isSystem = true,
                        bookCount = 100,
                        callerPermission = SharePermission.Read,
                        isOwner = false,
                    )
                val normalCollection =
                    Collection(
                        id = "col-1",
                        name = "Favourites",
                        ownerId = "user-1",
                        isInbox = false,
                        isSystem = false,
                        bookCount = 5,
                        callerPermission = SharePermission.Read,
                        isOwner = true,
                    )
                every { fixture.collectionRepository.observeCollections() } returns
                    flowOf(listOf(systemCollection, normalCollection))
                val viewModel = fixture.build()

                turbineScope {
                    val collections = viewModel.collections.testIn(backgroundScope)
                    collections.awaitItem() // initial emptyList seed
                    advanceUntilIdle()

                    // Then — only the non-system collection appears
                    val result = collections.expectMostRecentItem()
                    result.size shouldBe 1
                    result[0].id shouldBe "col-1"

                    collections.cancel()
                }
            }
        }
    })
