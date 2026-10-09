package com.calypsan.listenup.client.presentation.bookdetail

import app.cash.turbine.test
import app.cash.turbine.turbineScope
import com.calypsan.listenup.api.dto.auth.Permission
import com.calypsan.listenup.api.dto.match.AppliedChange
import com.calypsan.listenup.api.dto.match.LastMatch
import com.calypsan.listenup.api.dto.match.MetadataSource
import com.calypsan.listenup.api.dto.match.UndoResult
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.TestData
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.model.BookMatchRecord
import com.calypsan.listenup.client.domain.model.CachedUserProfile
import com.calypsan.listenup.client.domain.model.SeriesHierarchy
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.repository.BookAvailability
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import com.calypsan.listenup.client.domain.repository.DocumentRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.domain.repository.Reachability
import com.calypsan.listenup.client.domain.repository.SeriesRepository
import com.calypsan.listenup.client.domain.repository.ServerReachability
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import com.calypsan.listenup.client.domain.repository.TagRepository
import com.calypsan.listenup.client.domain.repository.UserProfileRepository
import com.calypsan.listenup.client.presentation.match.FakeMatchingRepository
import com.calypsan.listenup.client.presentation.match.UndoMatch
import com.calypsan.listenup.client.test.fake.FakeBookRepository
import com.calypsan.listenup.client.test.fake.FakeBookVisibilityRepository
import com.calypsan.listenup.client.test.fake.FakeInboxRepository
import com.calypsan.listenup.client.test.fake.FakePermissionsRepository
import com.calypsan.listenup.client.test.fake.FakeUserRepository
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Book Detail's "Undo last match" row: [BookDetailViewModel.lastMatch] is derived from Room and shows only while the
 * book is at the revision the match left it at, only to someone who may undo it; [BookDetailViewModel.undoLastMatch]
 * goes through the shared [UndoMatch] with the receipt's typed outcomes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookDetailViewModelLastMatchTest :
    FunSpec({
        val dispatcher = StandardTestDispatcher()
        beforeTest { Dispatchers.setMain(dispatcher) }
        afterTest { Dispatchers.resetMain() }

        val hardcover = MetadataSource("hardcover", "Hardcover")
        val match =
            LastMatch(
                receiptId = "receipt-1",
                appliedAt = 1_700_000_000_000L,
                appliedBy = "me",
                revision = 7L,
                changes =
                    listOf(
                        AppliedChange.Cover(hardcover),
                        AppliedChange.ChapterNames(count = 16, source = hardcover),
                    ),
            )

        class Fixture {
            val books = FakeBookRepository()
            val permissions = FakePermissionsRepository(Permission.EDIT_METADATA)
            val users = FakeUserRepository()
            val profiles = FakeProfiles()
            val matching = FakeMatchingRepository()
            val errorBus = ErrorBus()

            fun build(): BookDetailViewModel {
                val bookRepository: BookRepository =
                    mock {
                        every { observeBookDetail("book-1") } returns flowOf(TestData.bookDetail(id = "book-1"))
                        everySuspend { getChapters("book-1") } returns emptyList()
                        every { observeMatchRecord(any()) } returns books.observeMatchRecord("book-1")
                    }
                return BookDetailViewModel(
                    bookRepository = bookRepository,
                    tagRepository = mock<TagRepository> { every { observeAll() } returns flowOf(emptyList()) },
                    playbackPositionRepository =
                        mock<PlaybackPositionRepository> {
                            everySuspend { get(any<BookId>()) } returns AppResult.Success(null)
                        },
                    userRepository = users,
                    permissionsRepository = permissions,
                    shelfRepository =
                        mock<ShelfRepository> {
                            every { observeMyShelves(any()) } returns flowOf(emptyList())
                            every { observeShelvesContainingBook(any()) } returns flowOf(emptyList())
                        },
                    collectionRepository =
                        mock<CollectionRepository> { every { observeCollections() } returns flowOf(emptyList()) },
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
                                        canDownload = true,
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
                    documentRepository =
                        mock<DocumentRepository> { every { observeDocuments(any()) } returns flowOf(emptyList()) },
                    inboxRepository = FakeInboxRepository(),
                    bookVisibilityRepository = FakeBookVisibilityRepository(),
                    bookEditRepository = mock(),
                    seriesRepository =
                        mock<SeriesRepository> { every { observeHierarchy() } returns flowOf(SeriesHierarchy.Empty) },
                    undoMatch = UndoMatch(matching),
                    userProfileRepository = profiles,
                ).apply { loadBook("book-1") }
            }
        }

        test("the row shows the match while the book is at the revision the match left it at") {
            runTest(dispatcher) {
                val f = Fixture()
                f.books.setMatchRecord("book-1", BookMatchRecord(revision = 7L, lastMatch = match))
                val vm = f.build()

                vm.lastMatch.test {
                    dispatcher.scheduler.advanceUntilIdle()
                    val row = expectMostRecentItem().shouldNotBeNull()
                    row.receipt.receiptId shouldBe "receipt-1"
                    row.receipt.coverSource shouldBe hardcover
                    row.receipt.chapterNameCount shouldBe 16
                    row.receipt.changes shouldBe match.changes
                    row.appliedAtMs shouldBe match.appliedAt
                    row.showingChanges shouldBe false
                    row.undoing shouldBe false
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("any later change to the book retires the row") {
            runTest(dispatcher) {
                val f = Fixture()
                f.books.setMatchRecord("book-1", BookMatchRecord(revision = 7L, lastMatch = match))
                val vm = f.build()

                vm.lastMatch.test {
                    dispatcher.scheduler.advanceUntilIdle()
                    expectMostRecentItem().shouldNotBeNull()
                    f.books.setMatchRecord("book-1", BookMatchRecord(revision = 8L, lastMatch = match))
                    dispatcher.scheduler.advanceUntilIdle()
                    expectMostRecentItem().shouldBeNull()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("someone without Edit metadata never sees the row — the server would refuse their Undo") {
            runTest(dispatcher) {
                val f = Fixture()
                f.permissions.granted.value = emptySet()
                f.books.setMatchRecord("book-1", BookMatchRecord(revision = 7L, lastMatch = match))
                val vm = f.build()

                vm.lastMatch.test {
                    dispatcher.scheduler.advanceUntilIdle()
                    expectMostRecentItem().shouldBeNull()
                    f.permissions.granted.value = setOf(Permission.EDIT_METADATA)
                    dispatcher.scheduler.advanceUntilIdle()
                    expectMostRecentItem().shouldNotBeNull()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("the row names whoever matched the book when it wasn't you") {
            runTest(dispatcher) {
                val f = Fixture()
                f.users.currentUser.value = user("me")
                f.profiles.names["sam"] = "Sam"
                f.books.setMatchRecord("book-1", BookMatchRecord(revision = 7L, lastMatch = match.copy(appliedBy = "sam")))
                val vm = f.build()

                vm.lastMatch.test {
                    dispatcher.scheduler.advanceUntilIdle()
                    expectMostRecentItem().shouldNotBeNull().matchedBy shouldBe "Sam"
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("the row names nobody when you matched the book yourself") {
            runTest(dispatcher) {
                val f = Fixture()
                f.users.currentUser.value = user("me")
                f.profiles.names["me"] = "Me"
                f.books.setMatchRecord("book-1", BookMatchRecord(revision = 7L, lastMatch = match))
                val vm = f.build()

                vm.lastMatch.test {
                    dispatcher.scheduler.advanceUntilIdle()
                    expectMostRecentItem().shouldNotBeNull().matchedBy.shouldBeNull()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("See what changed opens the receipt's changes and closes again") {
            runTest(dispatcher) {
                val f = Fixture()
                f.books.setMatchRecord("book-1", BookMatchRecord(revision = 7L, lastMatch = match))
                val vm = f.build()

                vm.lastMatch.test {
                    dispatcher.scheduler.advanceUntilIdle()
                    expectMostRecentItem().shouldNotBeNull()
                    vm.seeWhatChanged()
                    dispatcher.scheduler.advanceUntilIdle()
                    expectMostRecentItem().shouldNotBeNull().showingChanges shouldBe true
                    vm.closeWhatChanged()
                    dispatcher.scheduler.advanceUntilIdle()
                    expectMostRecentItem().shouldNotBeNull().showingChanges shouldBe false
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("Undo last match undoes this receipt through the shared UndoMatch, then says it is undone") {
            runTest(dispatcher) {
                val f = Fixture()
                val gate = CompletableDeferred<Unit>()
                f.matching.undoReply = { receiptId ->
                    gate.await()
                    // The repository applies the restored book to Room before it returns.
                    f.books.setMatchRecord("book-1", BookMatchRecord(revision = 8L, lastMatch = null))
                    AppResult.Success(UndoResult(receiptId, match.changes))
                }
                f.books.setMatchRecord("book-1", BookMatchRecord(revision = 7L, lastMatch = match))
                val vm = f.build()

                turbineScope {
                    val rows = vm.lastMatch.testIn(backgroundScope)
                    val events = vm.lastMatchEvents.testIn(backgroundScope)
                    dispatcher.scheduler.advanceUntilIdle()
                    rows.expectMostRecentItem().shouldNotBeNull()

                    vm.undoLastMatch()
                    dispatcher.scheduler.advanceUntilIdle()
                    rows.expectMostRecentItem().shouldNotBeNull().undoing shouldBe true

                    gate.complete(Unit)
                    dispatcher.scheduler.advanceUntilIdle()
                    f.matching.undoRequests shouldBe listOf("receipt-1")
                    events.awaitItem() shouldBe LastMatchEvent.Undone
                    rows.expectMostRecentItem().shouldBeNull()
                    rows.cancelAndIgnoreRemainingEvents()
                    events.cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("an Undo the server calls too late hides the row at once and says why") {
            runTest(dispatcher) {
                val f = Fixture()
                f.matching.undoReply = { AppResult.Failure(MetadataError.UndoExpired()) }
                f.books.setMatchRecord("book-1", BookMatchRecord(revision = 7L, lastMatch = match))
                val vm = f.build()

                turbineScope {
                    val rows = vm.lastMatch.testIn(backgroundScope)
                    val events = vm.lastMatchEvents.testIn(backgroundScope)
                    dispatcher.scheduler.advanceUntilIdle()
                    rows.expectMostRecentItem().shouldNotBeNull()

                    vm.undoLastMatch()
                    dispatcher.scheduler.advanceUntilIdle()

                    events.awaitItem() shouldBe LastMatchEvent.Expired
                    rows.expectMostRecentItem().shouldBeNull()
                    rows.cancelAndIgnoreRemainingEvents()
                    events.cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("any other Undo failure reaches the error bus and keeps the row, with the error, to try again") {
            runTest(dispatcher) {
                val f = Fixture()
                val offline = TransportError.NetworkUnavailable()
                f.matching.undoReply = { AppResult.Failure(offline) }
                f.books.setMatchRecord("book-1", BookMatchRecord(revision = 7L, lastMatch = match))
                val vm = f.build()

                turbineScope {
                    val rows = vm.lastMatch.testIn(backgroundScope)
                    val errors = f.errorBus.errors.testIn(backgroundScope)
                    dispatcher.scheduler.advanceUntilIdle()
                    rows.expectMostRecentItem().shouldNotBeNull()

                    vm.undoLastMatch()
                    dispatcher.scheduler.advanceUntilIdle()

                    errors.awaitItem() shouldBe offline
                    val row = rows.expectMostRecentItem().shouldNotBeNull()
                    row.undoError shouldBe offline
                    row.undoing shouldBe false
                    rows.cancelAndIgnoreRemainingEvents()
                    errors.cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })

/** In-memory [UserProfileRepository]: display names by user id. */
private class FakeProfiles : UserProfileRepository {
    val names = mutableMapOf<String, String>()

    override suspend fun getById(userId: String): CachedUserProfile? = profile(userId)

    override fun observeProfile(userId: String): Flow<CachedUserProfile?> = flowOf(userId).map { profile(it) }

    private fun profile(userId: String): CachedUserProfile? =
        names[userId]?.let { CachedUserProfile(id = userId, displayName = it, avatarType = "auto", updatedAt = 0L) }
}

private fun user(id: String) =
    User(
        id = UserId(id),
        email = "$id@example.com",
        displayName = id,
        isAdmin = false,
        createdAtMs = 0L,
        updatedAtMs = 0L,
    )
