package com.calypsan.listenup.client.presentation.bookdetail

import app.cash.turbine.turbineScope
import com.calypsan.listenup.api.error.CollectionError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.TestData
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
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
import kotlinx.coroutines.CompletableDeferred
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
 * Book Detail for a book held in the admin inbox: [BookDetailUiState.Ready.isHeld] follows the Room
 * held set live, and [BookDetailViewModel.releaseFromInbox] releases exactly this book to everyone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookDetailViewModelHeldTest :
    FunSpec({
        val dispatcher = StandardTestDispatcher()
        beforeTest { Dispatchers.setMain(dispatcher) }
        afterTest { Dispatchers.resetMain() }

        class Fixture {
            val bookRepository: BookRepository = mock()
            val tagRepository: TagRepository = mock()
            val playbackPositionRepository: PlaybackPositionRepository = mock()
            val userRepository: UserRepository = mock()
            val shelfRepository: ShelfRepository = mock()
            val collectionRepository: CollectionRepository = mock()
            val documentRepository: DocumentRepository = mock()
            val inboxRepository = FakeInboxRepository()
            val heldIds get() = inboxRepository.held
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
                                        // Both ON: a held book's "off" must come from the hold, not the fixture.
                                        canDownload = true,
                                        showServerWarning = true,
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
                    bookVisibilityRepository = FakeBookVisibilityRepository(),
                    bookEditRepository = mock(),
                )
        }

        test("a held book's detail is triage-only: held, and neither playable nor downloadable") {
            runTest(dispatcher) {
                val f = Fixture()
                f.heldIds.value = setOf(BookId("book-1"))
                val vm = f.build()

                vm.loadBook("book-1")
                advanceUntilIdle()

                val ready = vm.state.value.shouldBeInstanceOf<BookDetailUiState.Ready>()
                ready.isHeld shouldBe true
                ready.canPlay shouldBe false
                ready.canDownload shouldBe false
                ready.showServerWarning shouldBe false
            }
        }

        test("an ordinary book carries no held section and keeps its actions") {
            runTest(dispatcher) {
                val f = Fixture()
                val vm = f.build()

                vm.loadBook("book-1")
                advanceUntilIdle()

                val ready = vm.state.value.shouldBeInstanceOf<BookDetailUiState.Ready>()
                ready.isHeld shouldBe false
                ready.canPlay shouldBe true
                ready.canDownload shouldBe true
                ready.showServerWarning shouldBe true
            }
        }

        test("isHeld follows the INBOX membership live") {
            runTest(dispatcher) {
                val f = Fixture()
                f.heldIds.value = setOf(BookId("book-1"))
                val vm = f.build()
                vm.loadBook("book-1")
                advanceUntilIdle()

                f.heldIds.value = emptySet()
                advanceUntilIdle()

                val ready = vm.state.value.shouldBeInstanceOf<BookDetailUiState.Ready>()
                ready.isHeld shouldBe false
                ready.canPlay shouldBe true
                ready.canDownload shouldBe true
            }
        }

        test("releaseFromInbox releases exactly this book, publicly, into its own library") {
            runTest(dispatcher) {
                val f = Fixture()
                f.heldIds.value = setOf(BookId("book-1"))
                val vm = f.build()
                vm.loadBook("book-1")
                advanceUntilIdle()

                vm.releaseFromInbox()
                advanceUntilIdle()

                f.inboxRepository.releases.single() shouldBe ("test-library" to mapOf("book-1" to emptyList<String>()))
                vm.state.value
                    .shouldBeInstanceOf<BookDetailUiState.Ready>()
                    .isReleasingFromInbox shouldBe false
            }
        }

        test("a refused release keeps the book held and reports the typed error") {
            runTest(dispatcher) {
                val f = Fixture()
                f.heldIds.value = setOf(BookId("book-1"))
                val refusal = ValidationError(message = "Only admins can release books.")
                f.inboxRepository.releaseResult = AppResult.Failure(refusal)
                val vm = f.build()
                vm.loadBook("book-1")
                advanceUntilIdle()

                turbineScope {
                    val errors = f.errorBus.errors.testIn(backgroundScope)

                    vm.releaseFromInbox()
                    advanceUntilIdle()

                    errors.awaitItem() shouldBe refusal
                    val ready = vm.state.value.shouldBeInstanceOf<BookDetailUiState.Ready>()
                    ready.isHeld shouldBe true
                    ready.isReleasingFromInbox shouldBe false
                    errors.cancel()
                }
            }
        }

        test("a release that leaves this book held reports it in words, and the book stays held") {
            runTest(dispatcher) {
                val f = Fixture()
                f.heldIds.value = setOf(BookId("book-1"))
                val incomplete = CollectionError.ReleaseIncomplete(failedBookIds = listOf("book-1"))
                f.inboxRepository.releaseResult = AppResult.Failure(incomplete)
                val vm = f.build()
                vm.loadBook("book-1")
                advanceUntilIdle()

                turbineScope {
                    val errors = f.errorBus.errors.testIn(backgroundScope)

                    vm.releaseFromInbox()
                    advanceUntilIdle()

                    val reported = errors.awaitItem()
                    reported shouldBe incomplete
                    // Every platform shows the bus's message as-is, so it must read as a sentence.
                    reported.message shouldBe "Some books couldn't be released. They're still in the inbox."
                    val ready = vm.state.value.shouldBeInstanceOf<BookDetailUiState.Ready>()
                    ready.isHeld shouldBe true
                    ready.isReleasingFromInbox shouldBe false
                    errors.cancel()
                }
            }
        }

        test("releaseFromInbox does nothing for a book that is not held") {
            runTest(dispatcher) {
                val f = Fixture()
                val vm = f.build()
                vm.loadBook("book-1")
                advanceUntilIdle()

                vm.releaseFromInbox()
                advanceUntilIdle()

                f.inboxRepository.releases shouldBe emptyList()
            }
        }

        test("a double tap on Release sends one release, not two") {
            runTest(dispatcher) {
                val f = Fixture()
                f.heldIds.value = setOf(BookId("book-1"))
                f.inboxRepository.releaseGate = CompletableDeferred()
                val vm = f.build()
                vm.loadBook("book-1")
                advanceUntilIdle()

                // Two taps inside one frame: nothing has been dispatched between them.
                vm.releaseFromInbox()
                vm.releaseFromInbox()
                advanceUntilIdle()

                f.inboxRepository.releases.size shouldBe 1
                f.inboxRepository.releaseGate?.complete(Unit)
                advanceUntilIdle()
            }
        }

        test("the Release button stays busy when the held set re-emits mid-release") {
            runTest(dispatcher) {
                val f = Fixture()
                f.heldIds.value = setOf(BookId("book-1"))
                f.inboxRepository.releaseGate = CompletableDeferred()
                val vm = f.build()
                vm.loadBook("book-1")
                advanceUntilIdle()

                vm.releaseFromInbox()
                advanceUntilIdle()
                // A scan holds another book while this release is still on the wire.
                f.heldIds.value = setOf(BookId("book-1"), BookId("book-2"))
                advanceUntilIdle()

                vm.state.value
                    .shouldBeInstanceOf<BookDetailUiState.Ready>()
                    .isReleasingFromInbox shouldBe true
                f.inboxRepository.releaseGate?.complete(Unit)
                advanceUntilIdle()
                vm.state.value
                    .shouldBeInstanceOf<BookDetailUiState.Ready>()
                    .isReleasingFromInbox shouldBe false
            }
        }
    })
