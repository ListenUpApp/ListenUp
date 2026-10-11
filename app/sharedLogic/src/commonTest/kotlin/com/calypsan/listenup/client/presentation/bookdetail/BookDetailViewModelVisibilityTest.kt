package com.calypsan.listenup.client.presentation.bookdetail

import com.calypsan.listenup.api.dto.auth.Permission
import com.calypsan.listenup.client.test.fake.FakePermissionsRepository
import app.cash.turbine.test
import com.calypsan.listenup.api.error.BookError
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
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
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
import com.calypsan.listenup.client.domain.model.SeriesHierarchy
import com.calypsan.listenup.client.domain.repository.SeriesRepository

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
            val permissionsRepository = FakePermissionsRepository(Permission.EDIT_METADATA)
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
                    permissionsRepository = permissionsRepository,
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
                    seriesRepository =
                        mock<SeriesRepository> {
                            every { observeHierarchy() } returns flowOf(SeriesHierarchy.Empty)
                        },
                    undoMatch = mock(),
                    userProfileRepository = mock(),
                )
        }

        test("visibility is null when the repository has none — a member's device") {
            runTest(dispatcher) {
                val vm = watch(Fixture().build())
                vm.loadBook("book-1")
                advanceUntilIdle()

                vm.state.value
                    .shouldBeInstanceOf<BookDetailUiState.Ready>()
                    .visibility shouldBe null
            }
        }

        test("visibility is populated for an admin and follows the repository live") {
            runTest(dispatcher) {
                val f = Fixture()
                f.visibility.setVisibility(bookId, restricted)
                val vm = watch(f.build())
                vm.loadBook("book-1")
                advanceUntilIdle()
                vm.state.value
                    .shouldBeInstanceOf<BookDetailUiState.Ready>()
                    .visibility shouldBe restricted

                f.visibility.setVisibility(bookId, BookVisibility.Public)
                advanceUntilIdle()
                vm.state.value
                    .shouldBeInstanceOf<BookDetailUiState.Ready>()
                    .visibility shouldBe BookVisibility.Public
            }
        }

        test("restoreToAllBooks sends an empty collection set for a stranded book, and stays restoring until re-homed") {
            runTest(dispatcher) {
                val f = Fixture()
                f.visibility.setVisibility(bookId, BookVisibility.Stranded)
                everySuspend { f.bookEditRepository.setBookCollections(bookId, emptyList()) } returns AppResult.Success(Unit)
                val vm = watch(f.build())
                vm.loadBook("book-1")
                advanceUntilIdle()

                vm.restoreToAllBooks()
                advanceUntilIdle()

                verifySuspend { f.bookEditRepository.setBookCollections(bookId, emptyList()) }
                vm.state.value
                    .shouldBeInstanceOf<BookDetailUiState.Ready>()
                    .isRestoringToAllBooks shouldBe true

                // The server's echo re-homes the book; the restoring state ends with it.
                f.visibility.setVisibility(bookId, BookVisibility.Public)
                advanceUntilIdle()
                val ready = vm.state.value.shouldBeInstanceOf<BookDetailUiState.Ready>()
                ready.visibility shouldBe BookVisibility.Public
                ready.isRestoringToAllBooks shouldBe false
            }
        }

        test("restoreToAllBooks does nothing unless the book is stranded") {
            runTest(dispatcher) {
                val f = Fixture()
                f.visibility.setVisibility(bookId, restricted)
                val vm = watch(f.build())
                vm.loadBook("book-1")
                advanceUntilIdle()

                vm.restoreToAllBooks()
                advanceUntilIdle()

                verifySuspend(VerifyMode.not) { f.bookEditRepository.setBookCollections(any(), any()) }
                vm.state.value
                    .shouldBeInstanceOf<BookDetailUiState.Ready>()
                    .isRestoringToAllBooks shouldBe false
            }
        }

        test("restoreToAllBooks never touches a held book — the inbox's Release owns it") {
            runTest(dispatcher) {
                val f = Fixture()
                f.inboxRepository.hold("book-1")
                f.visibility.setVisibility(bookId, BookVisibility.Held)
                val vm = watch(f.build())
                vm.loadBook("book-1")
                advanceUntilIdle()

                vm.restoreToAllBooks()
                advanceUntilIdle()

                verifySuspend(VerifyMode.not) { f.bookEditRepository.setBookCollections(any(), any()) }
            }
        }

        test("a refused restore clears the restoring state and reports the error") {
            runTest(dispatcher) {
                val f = Fixture()
                f.visibility.setVisibility(bookId, BookVisibility.Stranded)
                everySuspend { f.bookEditRepository.setBookCollections(bookId, emptyList()) } returns
                    AppResult.Failure(BookError.NotFound())
                val vm = watch(f.build())
                vm.loadBook("book-1")
                advanceUntilIdle()

                vm.restoreToAllBooks()
                advanceUntilIdle()

                val ready = vm.state.value.shouldBeInstanceOf<BookDetailUiState.Ready>()
                ready.visibility shouldBe BookVisibility.Stranded
                ready.isRestoringToAllBooks shouldBe false
            }
        }

        test("a double tap on Show to all members sends exactly one restore") {
            runTest(dispatcher) {
                val f = Fixture()
                f.visibility.setVisibility(bookId, BookVisibility.Stranded)
                everySuspend { f.bookEditRepository.setBookCollections(bookId, emptyList()) } returns AppResult.Success(Unit)
                val vm = watch(f.build())
                vm.loadBook("book-1")
                advanceUntilIdle()

                // Both taps land before the first launch runs — the busy flag must already be up.
                vm.restoreToAllBooks()
                vm.restoreToAllBooks()
                advanceUntilIdle()

                verifySuspend(VerifyMode.exactly(1)) { f.bookEditRepository.setBookCollections(bookId, emptyList()) }
            }
        }

        test("the restoring flag survives an unrelated rebuild while the book is still stranded") {
            runTest(dispatcher) {
                val f = Fixture()
                f.visibility.setVisibility(bookId, BookVisibility.Stranded)
                everySuspend { f.bookEditRepository.setBookCollections(bookId, emptyList()) } returns AppResult.Success(Unit)
                val vm = watch(f.build())
                vm.loadBook("book-1")
                advanceUntilIdle()
                vm.restoreToAllBooks()
                advanceUntilIdle()

                // Another book being held re-emits the held set, which rebuilds Ready from scratch.
                f.inboxRepository.hold("another-book")
                advanceUntilIdle()

                val ready = vm.state.value.shouldBeInstanceOf<BookDetailUiState.Ready>()
                ready.visibility shouldBe BookVisibility.Stranded
                ready.isRestoringToAllBooks shouldBe true
            }
        }

        test("a refused restore reports the typed error on the error bus") {
            runTest(dispatcher) {
                val f = Fixture()
                f.visibility.setVisibility(bookId, BookVisibility.Stranded)
                everySuspend { f.bookEditRepository.setBookCollections(bookId, emptyList()) } returns
                    AppResult.Failure(BookError.NotFound())
                val vm = watch(f.build())
                vm.loadBook("book-1")
                advanceUntilIdle()

                f.errorBus.errors.test {
                    vm.restoreToAllBooks()
                    advanceUntilIdle()
                    awaitItem() shouldBe BookError.NotFound()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("restoreToAllBooks trusts the held set even while visibility still reads Stranded") {
            runTest(dispatcher) {
                val f = Fixture()
                // The held set and the visibility are two Room flows; for an emission they can
                // disagree. The held set wins: a held book is the inbox's to release.
                f.inboxRepository.hold("book-1")
                f.visibility.setVisibility(bookId, BookVisibility.Stranded)
                val vm = watch(f.build())
                vm.loadBook("book-1")
                advanceUntilIdle()

                vm.restoreToAllBooks()
                advanceUntilIdle()

                verifySuspend(VerifyMode.not) { f.bookEditRepository.setBookCollections(any(), any()) }
            }
        }
    })
