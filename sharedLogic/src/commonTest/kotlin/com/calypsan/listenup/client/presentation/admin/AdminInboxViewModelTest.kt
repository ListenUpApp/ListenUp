package com.calypsan.listenup.client.presentation.admin

import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.client.data.local.db.BookContributorCrossRef
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.BookEntity
import com.calypsan.listenup.client.data.local.db.BookWithContributors
import com.calypsan.listenup.client.data.local.db.ContributorEntity
import com.calypsan.listenup.client.domain.model.AccessMode
import com.calypsan.listenup.client.domain.model.AdminEvent
import com.calypsan.listenup.client.domain.model.Library
import com.calypsan.listenup.client.domain.repository.EventStreamRepository
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.InboxRepository
import com.calypsan.listenup.client.domain.repository.LibraryRepository
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Tests for [AdminInboxViewModel] (Collections-2a — 1b admin inbox REST).
 *
 * The inbox lists book ids via [InboxRepository.listInbox] and hydrates them into
 * [com.calypsan.listenup.client.domain.model.InboxBookItem] projections by observing Room.
 * Release maps every selected id to an empty target-collection list (all inbox releases are
 * public/uncollected) and dispatches a single [InboxRepository.releaseBooks].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdminInboxViewModelTest :
    FunSpec({
        val dispatcher = StandardTestDispatcher()
        beforeSpec { Dispatchers.setMain(dispatcher) }
        afterSpec { Dispatchers.resetMain() }

        // Builds a BookWithContributors with a single author for hydration tests.
        fun bookWith(
            id: String,
            title: String,
            author: String,
            durationMs: Long = 3_600_000L,
        ): BookWithContributors {
            val authorId = "$id-author"
            return BookWithContributors(
                book =
                    BookEntity(
                        id = BookId(id),
                        libraryId = LibraryId("lib1"),
                        folderId = FolderId("folder1"),
                        title = title,
                        totalDuration = durationMs,
                        createdAt = Timestamp(0L),
                        updatedAt = Timestamp(0L),
                    ),
                contributors =
                    listOf(
                        ContributorEntity(
                            id = ContributorId(authorId),
                            name = author,
                            description = null,
                            imagePath = null,
                            createdAt = Timestamp(0L),
                            updatedAt = Timestamp(0L),
                        ),
                    ),
                contributorRoles =
                    listOf(
                        BookContributorCrossRef(
                            bookId = BookId(id),
                            contributorId = ContributorId(authorId),
                            role = "author",
                        ),
                    ),
                series = emptyList(),
                seriesSequences = emptyList(),
            )
        }

        class Fixture {
            val inboxRepo: InboxRepository = mock()
            val libraryRepo: LibraryRepository = mock()
            val eventStream: EventStreamRepository = mock()
            val bookDao: BookDao = mock()
            val imageStorage: ImageStorage = mock()
            val adminEvents = MutableSharedFlow<AdminEvent>()

            init {
                // Default: no hydrated books (overridden per-test for hydration cases).
                every { bookDao.observeByIdsWithContributors(any()) } returns flowOf(emptyList())
                every { imageStorage.exists(any()) } returns false
                every { imageStorage.getCoverPath(any()) } returns ""
            }

            fun build(): AdminInboxViewModel {
                every { libraryRepo.observeAll() } returns
                    MutableStateFlow(
                        listOf(
                            Library(
                                id = "lib1",
                                name = "Main",
                                metadataPrecedence = "",
                                accessMode = AccessMode.OPEN,
                                createdByUserId = null,
                                createdAt = 0L,
                                revision = 0L,
                            ),
                        ),
                    )
                every { eventStream.adminEvents } returns adminEvents
                return AdminInboxViewModel(inboxRepo, libraryRepo, eventStream, bookDao, imageStorage, ErrorBus())
            }
        }

        test("Ready emitted with inbox book ids") {
            runTest(dispatcher) {
                val f = Fixture()
                everySuspend { f.inboxRepo.listInbox("lib1") } returns AppResult.Success(listOf("b1", "b2"))
                val vm = f.build()
                advanceUntilIdle()
                val ready = vm.state.value.shouldBeInstanceOf<AdminInboxUiState.Ready>()
                ready.bookIds shouldBe listOf("b1", "b2")
            }
        }

        test("Ready hydrates inbox ids into InboxBookItems via BookDao") {
            runTest(dispatcher) {
                val f = Fixture()
                everySuspend { f.inboxRepo.listInbox("lib1") } returns AppResult.Success(listOf("b1", "b2"))
                every { f.bookDao.observeByIdsWithContributors(any()) } returns
                    flowOf(
                        listOf(
                            bookWith(id = "b1", title = "The Way of Kings", author = "Brandon Sanderson"),
                            bookWith(id = "b2", title = "Mistborn", author = "Brandon Sanderson"),
                        ),
                    )
                val vm = f.build()
                advanceUntilIdle()

                val ready = vm.state.value.shouldBeInstanceOf<AdminInboxUiState.Ready>()
                ready.bookIds shouldBe listOf("b1", "b2")
                val first = ready.books.first { it.id == "b1" }
                first.title shouldBe "The Way of Kings"
                first.author shouldBe "Brandon Sanderson"
                first.durationMs shouldBe 3_600_000L
            }
        }

        test("listInbox failure yields Error on initial load") {
            runTest(dispatcher) {
                val f = Fixture()
                everySuspend { f.inboxRepo.listInbox("lib1") } returns
                    AppResult.Failure(ValidationError(message = "forbidden"))
                val vm = f.build()
                advanceUntilIdle()
                val err = vm.state.value.shouldBeInstanceOf<AdminInboxUiState.Error>()
                err.message shouldBe "forbidden"
            }
        }

        test("releaseSelected releases every selected book as public and dispatches one release") {
            runTest(dispatcher) {
                val f = Fixture()
                everySuspend { f.inboxRepo.listInbox("lib1") } returns AppResult.Success(listOf("b1", "b2"))
                everySuspend { f.inboxRepo.releaseBooks(any(), any()) } returns AppResult.Success(Unit)
                val vm = f.build()
                advanceUntilIdle()

                vm.toggleBookSelection("b1")
                vm.toggleBookSelection("b2")
                vm.releaseSelected()
                advanceUntilIdle()

                verifySuspend {
                    f.inboxRepo.releaseBooks("lib1", mapOf("b1" to emptyList(), "b2" to emptyList()))
                }
                val ready = vm.state.value.shouldBeInstanceOf<AdminInboxUiState.Ready>()
                ready.bookIds shouldBe emptyList()
                ready.lastReleasedCount shouldBe 2
            }
        }

        test("releaseSelected failure surfaces a transient error and keeps books") {
            runTest(dispatcher) {
                val f = Fixture()
                everySuspend { f.inboxRepo.listInbox("lib1") } returns AppResult.Success(listOf("b1"))
                everySuspend { f.inboxRepo.releaseBooks(any(), any()) } returns
                    AppResult.Failure(ValidationError(message = "release failed"))
                val vm = f.build()
                advanceUntilIdle()

                vm.toggleBookSelection("b1")
                vm.releaseSelected()
                advanceUntilIdle()

                val ready = vm.state.value.shouldBeInstanceOf<AdminInboxUiState.Ready>()
                ready.error shouldBe "release failed"
                ready.bookIds shouldBe listOf("b1")
                ready.isReleasing shouldBe false
            }
        }

        test("InboxBookReleased SSE event removes the book locally") {
            runTest(dispatcher) {
                val f = Fixture()
                everySuspend { f.inboxRepo.listInbox("lib1") } returns AppResult.Success(listOf("b1", "b2"))
                val vm = f.build()
                advanceUntilIdle()

                f.adminEvents.emit(AdminEvent.InboxBookReleased(bookId = "b1"))
                advanceUntilIdle()

                val ready = vm.state.value.shouldBeInstanceOf<AdminInboxUiState.Ready>()
                ready.bookIds shouldBe listOf("b2")
            }
        }

        test("ids with no Room row are omitted from books but still counted in bookIds") {
            runTest(dispatcher) {
                val f = Fixture()
                everySuspend { f.inboxRepo.listInbox("lib1") } returns AppResult.Success(listOf("b1", "b2"))
                // Only b1 has synced into Room; b2 is an unhydrated inbox id.
                every { f.bookDao.observeByIdsWithContributors(any()) } returns
                    flowOf(listOf(bookWith(id = "b1", title = "The Way of Kings", author = "Brandon Sanderson")))
                val vm = f.build()
                advanceUntilIdle()

                val ready = vm.state.value.shouldBeInstanceOf<AdminInboxUiState.Ready>()
                ready.bookIds shouldBe listOf("b1", "b2")
                ready.books.map { it.id } shouldBe listOf("b1")
            }
        }

        test("releaseSelected success prunes the released id from selection and books") {
            runTest(dispatcher) {
                val f = Fixture()
                everySuspend { f.inboxRepo.listInbox("lib1") } returns AppResult.Success(listOf("b1", "b2"))
                everySuspend { f.inboxRepo.releaseBooks(any(), any()) } returns AppResult.Success(Unit)
                every { f.bookDao.observeByIdsWithContributors(any()) } returns
                    flowOf(
                        listOf(
                            bookWith(id = "b1", title = "The Way of Kings", author = "Brandon Sanderson"),
                            bookWith(id = "b2", title = "Mistborn", author = "Brandon Sanderson"),
                        ),
                    )
                val vm = f.build()
                advanceUntilIdle()

                vm.toggleBookSelection("b1")
                vm.releaseSelected()
                advanceUntilIdle()

                val ready = vm.state.value.shouldBeInstanceOf<AdminInboxUiState.Ready>()
                ready.selectedBookIds shouldBe emptySet()
                ready.bookIds shouldBe listOf("b2")
                ready.books.map { it.id } shouldBe listOf("b2")
            }
        }

        test("InboxBookReleased SSE echo prunes from bookIds and books and is idempotent") {
            runTest(dispatcher) {
                val f = Fixture()
                everySuspend { f.inboxRepo.listInbox("lib1") } returns AppResult.Success(listOf("b1", "b2"))
                every { f.bookDao.observeByIdsWithContributors(any()) } returns
                    flowOf(
                        listOf(
                            bookWith(id = "b1", title = "The Way of Kings", author = "Brandon Sanderson"),
                            bookWith(id = "b2", title = "Mistborn", author = "Brandon Sanderson"),
                        ),
                    )
                val vm = f.build()
                advanceUntilIdle()

                f.adminEvents.emit(AdminEvent.InboxBookReleased(bookId = "b1"))
                advanceUntilIdle()

                var ready = vm.state.value.shouldBeInstanceOf<AdminInboxUiState.Ready>()
                ready.bookIds shouldBe listOf("b2")
                ready.books.map { it.id } shouldBe listOf("b2")

                // A second echo for the already-removed id no-ops (no crash, state unchanged).
                f.adminEvents.emit(AdminEvent.InboxBookReleased(bookId = "b1"))
                advanceUntilIdle()

                ready = vm.state.value.shouldBeInstanceOf<AdminInboxUiState.Ready>()
                ready.bookIds shouldBe listOf("b2")
                ready.books.map { it.id } shouldBe listOf("b2")
            }
        }
    })
