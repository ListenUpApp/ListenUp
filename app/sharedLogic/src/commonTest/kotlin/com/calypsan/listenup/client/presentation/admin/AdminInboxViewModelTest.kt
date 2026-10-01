package com.calypsan.listenup.client.presentation.admin

import com.calypsan.listenup.api.dto.scan.ScanIssue
import com.calypsan.listenup.api.dto.scan.ScanIssueReason
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.result.AppResult
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
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * In-memory [InboxRepository]. [held] stands in for Room's held set, and a successful release removes
 * the books from it — exactly what the real write-through does — so these specs exercise the
 * ViewModel's real convergence path instead of a local prune.
 */
private class FakeInboxRepository : InboxRepository {
    val held = MutableStateFlow<Set<BookId>>(emptySet())
    var heldSource: Flow<Set<BookId>>? = null
    var releaseResult: AppResult<Unit> = AppResult.Success(Unit)
    val releases = mutableListOf<Pair<String, Map<String, List<String>>>>()
    var scanIssues: AppResult<List<ScanIssue>> = AppResult.Success(emptyList())
    var scanIssueLoads = 0
    var dismissResult: AppResult<Unit> = AppResult.Success(Unit)

    fun hold(vararg ids: String) {
        held.update { current -> current + ids.map { BookId(it) } }
    }

    override fun observeHeldBookIds(): Flow<Set<BookId>> = heldSource ?: held

    override suspend fun releaseBooks(
        libraryId: String,
        assignments: Map<String, List<String>>,
    ): AppResult<Unit> {
        releases += libraryId to assignments
        if (releaseResult is AppResult.Success) {
            held.update { current -> current.filterNot { it.value in assignments.keys }.toSet() }
        }
        return releaseResult
    }

    override suspend fun listScanIssues(): AppResult<List<ScanIssue>> {
        scanIssueLoads++
        return scanIssues
    }

    override suspend fun dismissScanIssue(issueId: String): AppResult<Unit> = dismissResult
}

/**
 * [AdminInboxViewModel] lists exactly the Room held set, follows it live, hydrates each id from Room,
 * and leaves the scan issues on their RPC.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdminInboxViewModelTest :
    FunSpec({
        val dispatcher = StandardTestDispatcher()
        beforeSpec { Dispatchers.setMain(dispatcher) }
        afterSpec { Dispatchers.resetMain() }

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
                        BookContributorCrossRef(bookId = BookId(id), contributorId = ContributorId(authorId), role = "author"),
                    ),
                series = emptyList(),
                seriesSequences = emptyList(),
            )
        }

        class Fixture {
            val inbox = FakeInboxRepository()
            val libraryRepo: LibraryRepository = mock()
            val eventStream: EventStreamRepository = mock()
            val bookDao: BookDao = mock()
            val imageStorage: ImageStorage = mock()
            val adminEvents = MutableSharedFlow<AdminEvent>()

            init {
                every { bookDao.observeByIdsWithContributors(any()) } returns flowOf(emptyList())
                every { imageStorage.exists(any()) } returns false
                every { imageStorage.getCoverPath(any()) } returns ""
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
            }

            fun build(): AdminInboxViewModel = AdminInboxViewModel(inbox, libraryRepo, eventStream, bookDao, imageStorage, ErrorBus())
        }

        test("lists exactly the Room held set, oldest hold first") {
            runTest(dispatcher) {
                val f = Fixture()
                f.inbox.hold("b1", "b2")
                val vm = f.build()
                advanceUntilIdle()

                vm.state.value.shouldBeInstanceOf<AdminInboxUiState.Ready>().bookIds shouldBe listOf("b1", "b2")
            }
        }

        test("hydrates held ids into InboxBookItems from Room") {
            runTest(dispatcher) {
                val f = Fixture()
                f.inbox.hold("b1", "b2")
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
                val first = ready.books.first { it.id == "b1" }
                first.title shouldBe "The Way of Kings"
                first.author shouldBe "Brandon Sanderson"
                first.durationMs shouldBe 3_600_000L
            }
        }

        test("a held id whose book has not synced is counted, and joins the list when it lands") {
            runTest(dispatcher) {
                val f = Fixture()
                f.inbox.hold("b1", "b2")
                every { f.bookDao.observeByIdsWithContributors(any()) } returns
                    flowOf(listOf(bookWith(id = "b1", title = "The Way of Kings", author = "Brandon Sanderson")))
                val vm = f.build()
                advanceUntilIdle()

                val ready = vm.state.value.shouldBeInstanceOf<AdminInboxUiState.Ready>()
                ready.bookIds shouldBe listOf("b1", "b2")
                ready.books.map { it.id } shouldBe listOf("b1")
            }
        }

        test("follows the held set live, and drops a vanished book from the selection") {
            runTest(dispatcher) {
                val f = Fixture()
                f.inbox.hold("b1", "b2")
                val vm = f.build()
                advanceUntilIdle()
                vm.toggleBookSelection("b2")

                // Another admin released b2; a scan held b3. Both arrive through sync, i.e. Room.
                f.inbox.held.value = setOf(BookId("b1"), BookId("b3"))
                advanceUntilIdle()

                val ready = vm.state.value.shouldBeInstanceOf<AdminInboxUiState.Ready>()
                ready.bookIds shouldBe listOf("b1", "b3")
                withClue("a book that is no longer held cannot stay selected for release") {
                    ready.selectedBookIds shouldBe emptySet()
                }
            }
        }

        test("releaseSelected dispatches one public release, and the books leave through Room") {
            runTest(dispatcher) {
                val f = Fixture()
                f.inbox.hold("b1", "b2", "b3")
                val vm = f.build()
                advanceUntilIdle()

                vm.toggleBookSelection("b1")
                vm.toggleBookSelection("b2")
                vm.releaseSelected()
                advanceUntilIdle()

                f.inbox.releases.single() shouldBe ("lib1" to mapOf("b1" to emptyList<String>(), "b2" to emptyList()))
                val ready = vm.state.value.shouldBeInstanceOf<AdminInboxUiState.Ready>()
                ready.bookIds shouldBe listOf("b3")
                ready.selectedBookIds shouldBe emptySet()
                ready.lastReleasedCount shouldBe 2
                ready.isReleasing shouldBe false
            }
        }

        test("a refused release surfaces a transient error and keeps the books") {
            runTest(dispatcher) {
                val f = Fixture()
                f.inbox.hold("b1")
                f.inbox.releaseResult = AppResult.Failure(ValidationError(message = "release failed"))
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

        test("a failing local read is an Error, and Retry recovers") {
            runTest(dispatcher) {
                val f = Fixture()
                f.inbox.heldSource = flow { throw IllegalStateException("disk I/O error") }
                val vm = f.build()
                advanceUntilIdle()

                vm.state.value.shouldBeInstanceOf<AdminInboxUiState.Error>()

                f.inbox.heldSource = null
                f.inbox.hold("b1")
                vm.loadInboxBooks()
                advanceUntilIdle()

                vm.state.value.shouldBeInstanceOf<AdminInboxUiState.Ready>().bookIds shouldBe listOf("b1")
            }
        }

        test("a scan that adds a book reloads the scan issues; the list itself needs no event") {
            runTest(dispatcher) {
                val f = Fixture()
                val vm = f.build()
                advanceUntilIdle()
                val loadsBefore = f.inbox.scanIssueLoads

                f.adminEvents.emit(AdminEvent.InboxBookAdded(bookId = "b9", title = "Oathbringer"))
                advanceUntilIdle()

                f.inbox.scanIssueLoads shouldBe loadsBefore + 1
                withClue("held books come from Room; the event must not invent one") {
                    vm.state.value.shouldBeInstanceOf<AdminInboxUiState.Ready>().bookIds shouldBe emptyList()
                }
            }
        }

        test("scan issues load alongside the held books") {
            runTest(dispatcher) {
                val f = Fixture()
                f.inbox.scanIssues =
                    AppResult.Success(
                        listOf(
                            ScanIssue(
                                id = "i1",
                                rootRelPath = "Author/No Audio Here",
                                reason = ScanIssueReason.NO_RECOGNIZED_AUDIO,
                                detail = "found: cover.jpg",
                                firstSeenAt = 1L,
                                lastSeenAt = 2L,
                            ),
                        ),
                    )
                val vm = f.build()
                advanceUntilIdle()

                val ready = vm.state.value.shouldBeInstanceOf<AdminInboxUiState.Ready>()
                ready.scanIssues.single().rootRelPath shouldBe "Author/No Audio Here"
                withClue("issues with no held books is a POPULATED inbox, not an empty one") {
                    ready.isEmpty shouldBe false
                }
            }
        }

        test("a failure loading issues does not take the whole inbox down with it") {
            runTest(dispatcher) {
                val f = Fixture()
                f.inbox.hold("b1")
                f.inbox.scanIssues = AppResult.Failure(TransportError.NetworkUnavailable())
                val vm = f.build()
                advanceUntilIdle()

                withClue("the held-books half is independently useful — show what we do have") {
                    val ready = vm.state.value.shouldBeInstanceOf<AdminInboxUiState.Ready>()
                    ready.bookIds shouldBe listOf("b1")
                    ready.scanIssues shouldBe emptyList()
                }
            }
        }

        test("dismissing an issue drops it without a reload") {
            runTest(dispatcher) {
                val f = Fixture()
                f.inbox.scanIssues =
                    AppResult.Success(
                        listOf(
                            ScanIssue("i1", "Author/A", ScanIssueReason.FILE_UNREADABLE, null, 1L, 1L),
                            ScanIssue("i2", "Author/B", ScanIssueReason.FILE_UNREADABLE, null, 1L, 1L),
                        ),
                    )
                val vm = f.build()
                advanceUntilIdle()

                vm.dismissScanIssue("i1")
                advanceUntilIdle()

                vm.state.value.shouldBeInstanceOf<AdminInboxUiState.Ready>().scanIssues.map { it.id } shouldBe listOf("i2")
            }
        }
    })
