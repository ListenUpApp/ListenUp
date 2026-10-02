package com.calypsan.listenup.client.presentation.bookdetail

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.TestData
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.model.BookVisibility
import com.calypsan.listenup.client.domain.model.CollectionRef
import com.calypsan.listenup.client.domain.model.HiddenFrom
import com.calypsan.listenup.client.domain.repository.BookAvailability
import com.calypsan.listenup.client.domain.repository.BookEditRepository
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import com.calypsan.listenup.client.domain.repository.DocumentRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.domain.repository.Reachability
import com.calypsan.listenup.client.domain.repository.ServerReachability
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import com.calypsan.listenup.client.domain.repository.TagRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.test.fake.FakeBookVisibilityRepository
import com.calypsan.listenup.client.test.fake.FakeInboxRepository
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.error.ErrorBus
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * [BookDetailUiState.Ready.visibility] — null on a member's device, populated and live on an
 * admin's — and the stranded-book fix [BookDetailViewModel.restoreToAllBooks].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookDetailViewModelVisibilityTest :
    FunSpec({
        val dispatcher = StandardTestDispatcher()
        beforeTest { Dispatchers.setMain(dispatcher) }
        afterTest { Dispatchers.resetMain() }

        val bookId = BookId("book-1")
        val restricted =
            BookVisibility.Restricted(listOf(CollectionRef("c1", "Kids")), HiddenFrom.Members(listOf("Bob")))

        class Fixture {
            val bookRepository: BookRepository = mock()
            val tagRepository: TagRepository = mock()
            val playbackPositionRepository: PlaybackPositionRepository = mock()
            val userRepository: UserRepository = mock()
            val shelfRepository: ShelfRepository = mock()
            val collectionRepository: CollectionRepository = mock()
            val documentRepository: DocumentRepository = mock()
            val bookEditRepository: BookEditRepository = mock()
            val inboxRepository = FakeInboxRepository()
            val visibility = FakeBookVisibilityRepository()
            val errorBus = ErrorBus()

            init {
                everySuspend { playbackPositionRepository.get(any<BookId>()) } returns AppResult.Success(null)
                every { userRepository.observeCurrentUser() } returns flowOf(null)
                every { userRepository.observeIsAdmin() } returns flowOf(true)
                every { shelfRepository.observeMyShelves(any()) } returns flowOf(emptyList())
                every { shelfRepository.observeShelvesContainingBook(any()) } returns flowOf(emptyList())
                every { tagRepository.observeAll() } returns flowOf(emptyList())
                every { documentRepository.observeDocuments(any()) } returns flowOf(emptyList())
                every { collectionRepository.observeCollections() } returns flowOf(emptyList())
                every { bookRepository.observeBookDetail("book-1") } returns flowOf(TestData.bookDetail(id = "book-1"))
                everySuspend { bookRepository.getChapters("book-1") } returns emptyList()
            }

            fun build(): BookDetailViewModel =
                BookDetailViewModel(
                    bookRepository = bookRepository,
                    tagRepository = tagRepository,
                    playbackPositionRepository = playbackPositionRepository,
                    userRepository = userRepository,
                    shelfRepository = shelfRepository,
                    collectionRepository = collectionRepository,
                    addBooksToShelfUseCase = mock(),
                    createShelfUseCase = mock(),
                    errorBus = errorBus,
                    bookAvailability =
                        object : BookAvailability {
                            override fun observe(bookId: BookId): Flow<BookAvailability.State> =
                                MutableStateFlow(
                                    BookAvailability.State(
                                        downloadStatus = BookDownloadStatus.NotDownloaded(""),
                                        isPlaybackAvailable = true,
                                        canPlay = true,
                                        canDownload = false,
                                        showServerWarning = false,
                                        isWaitingForWifi = false,
                                    ),
                                )
                        },
                    serverReachability =
                        object : ServerReachability {
                            override val state = MutableStateFlow<Reachability>(Reachability.Unknown)

                            override suspend fun retry() = Unit
                        },
                    documentRepository = documentRepository,
                    inboxRepository = inboxRepository,
                    bookVisibilityRepository = visibility,
                    bookEditRepository = bookEditRepository,
                )
        }

        test("visibility is null when the repository has none — a member's device") {
            runTest(dispatcher) {
                val vm = Fixture().build()
                vm.loadBook("book-1")
                advanceUntilIdle()

                vm.state.value.shouldBeInstanceOf<BookDetailUiState.Ready>().visibility shouldBe null
            }
        }

        test("visibility is populated for an admin and follows the repository live") {
            runTest(dispatcher) {
                val f = Fixture()
                f.visibility.setVisibility(bookId, restricted)
                val vm = f.build()
                vm.loadBook("book-1")
                advanceUntilIdle()
                vm.state.value.shouldBeInstanceOf<BookDetailUiState.Ready>().visibility shouldBe restricted

                f.visibility.setVisibility(bookId, BookVisibility.Public)
                advanceUntilIdle()
                vm.state.value.shouldBeInstanceOf<BookDetailUiState.Ready>().visibility shouldBe BookVisibility.Public
            }
        }
    })
