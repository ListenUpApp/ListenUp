package com.calypsan.listenup.client.presentation.bookdetail

import com.calypsan.listenup.api.dto.auth.Permission
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.TestData
import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.client.domain.model.SeriesHierarchy
import com.calypsan.listenup.client.domain.model.Tag
import com.calypsan.listenup.client.domain.repository.BookAvailability
import com.calypsan.listenup.client.domain.repository.BookEditRepository
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import com.calypsan.listenup.client.domain.repository.DocumentRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.domain.repository.Reachability
import com.calypsan.listenup.client.domain.repository.SeriesRepository
import com.calypsan.listenup.client.domain.repository.ServerReachability
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import com.calypsan.listenup.client.domain.repository.TagRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.domain.usecase.shelf.AddBooksToShelfUseCase
import com.calypsan.listenup.client.test.fake.FakeBookVisibilityRepository
import com.calypsan.listenup.client.test.fake.FakeInboxRepository
import com.calypsan.listenup.client.test.fake.FakePermissionsRepository
import com.calypsan.listenup.client.playback.BookPlayhead
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * One [BookDetailViewModel] over controllable inputs: every Room-shaped input is a [MutableStateFlow] a spec can
 * push, every write is a stub a spec can gate, and [liveDetailObservers] counts how many book observers are
 * running right now — so a spec can see whether the ViewModel is holding Room open.
 */
internal class BookDetailFixture {
    private val details = mutableMapOf<String, MutableStateFlow<BookDetail?>>()

    /** Book-detail observers currently subscribed (all ids). */
    var liveDetailObservers = 0
        private set

    val availability =
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
    val adminStatus = MutableStateFlow(false)
    val allTags = MutableStateFlow<List<Tag>>(emptyList())
    val hierarchy = MutableStateFlow(SeriesHierarchy.Empty)
    val permissions = FakePermissionsRepository(Permission.EDIT_METADATA)
    val inbox = FakeInboxRepository()
    val visibility = FakeBookVisibilityRepository()
    val errorBus = ErrorBus()

    /** The saved position Room holds for the book; specs push it, and the progress writes move it like Room does. */
    val position = MutableStateFlow<PlaybackPosition?>(null)

    /** The player's loaded book; nothing by default. */
    val loadedBookId = MutableStateFlow<BookId?>(null)

    /** The player's live playhead and the book it belongs to; read only when that book is this one. */
    val playhead = MutableStateFlow<BookPlayhead?>(null)

    /** The book's chapters as Room holds them. */
    val chapters = MutableStateFlow<List<Chapter>>(emptyList())

    var markCompleteGate: CompletableDeferred<AppResult<Unit>>? = null
    var addToShelfGate: CompletableDeferred<AppResult<Int>>? = null
    var addToShelfResult: AppResult<Int> = AppResult.Success(1)
    var deleteResult: AppResult<Unit> = AppResult.Success(Unit)

    /** The Room row for [bookId]; a fresh book with that id until a spec sets it (null = the row is gone). */
    fun detail(bookId: String): MutableStateFlow<BookDetail?> =
        details.getOrPut(bookId) { MutableStateFlow(TestData.bookDetail(id = bookId)) }

    private fun observedDetail(bookId: String): Flow<BookDetail?> =
        detail(bookId)
            .onStart { liveDetailObservers++ }
            .onCompletion { liveDetailObservers-- }

    val bookRepository: BookRepository =
        mock<BookRepository> {
            every { observeBookDetail(any()) } calls { (id: String) -> observedDetail(id) }
            every { observeChapters(any()) } returns chapters
            everySuspend { deleteBook(any()) } calls { deleteResult }
            every { observeMatchRecord(any()) } returns flowOf(null)
        }
    val playbackPositionRepository: PlaybackPositionRepository =
        mock<PlaybackPositionRepository> {
            every { observe(any()) } returns position
            everySuspend { markComplete(any(), any(), any()) } calls
                { (_: BookId, startedAt: Long?, finishedAt: Long?) ->
                    val answer = markCompleteGate?.await() ?: AppResult.Success(Unit)
                    if (answer is AppResult.Success) {
                        position.value =
                            (position.value ?: positionOf(0L)).copy(
                                isFinished = true,
                                startedAtMs = startedAt ?: position.value?.startedAtMs,
                                finishedAtMs = finishedAt ?: FIXTURE_NOW_MS,
                            )
                    }
                    answer
                }
            everySuspend { discardProgress(any()) } calls {
                position.value = null
                AppResult.Success(Unit)
            }
            everySuspend { restartBook(any()) } calls {
                position.value = position.value?.copy(positionMs = 0L, isFinished = false, finishedAtMs = null)
                AppResult.Success(Unit)
            }
        }
    val collectionRepository: CollectionRepository =
        mock<CollectionRepository> {
            every { observeCollections() } returns flowOf(emptyList())
            everySuspend { addBook(any(), any()) } returns AppResult.Success(true)
        }
    val bookEditRepository: BookEditRepository =
        mock<BookEditRepository> {
            everySuspend { setBookCollections(any(), any()) } returns AppResult.Success(Unit)
        }
    private val userRepository: UserRepository =
        mock<UserRepository> {
            every { observeIsAdmin() } returns adminStatus
            every { observeCurrentUser() } returns flowOf(null)
        }
    private val tagRepository: TagRepository = mock<TagRepository> { every { observeAll() } returns allTags }
    private val shelfRepository: ShelfRepository =
        mock<ShelfRepository> {
            every { observeMyShelves(any()) } returns flowOf(emptyList())
            every { observeShelvesContainingBook(any()) } returns flowOf(emptyList())
        }
    private val addBooksToShelfUseCase: AddBooksToShelfUseCase =
        mock<AddBooksToShelfUseCase> {
            everySuspend { invoke(any(), any()) } calls { addToShelfGate?.await() ?: addToShelfResult }
        }
    private val documentRepository: DocumentRepository =
        mock<DocumentRepository> { every { observeDocuments(any()) } returns flowOf(emptyList()) }
    private val seriesRepository: SeriesRepository =
        mock<SeriesRepository> {
            every { observeHierarchy() } returns hierarchy
        }

    fun build(): BookDetailViewModel =
        BookDetailViewModel(
            bookRepository = bookRepository,
            tagRepository = tagRepository,
            playbackPositionRepository = playbackPositionRepository,
            userRepository = userRepository,
            permissionsRepository = permissions,
            shelfRepository = shelfRepository,
            collectionRepository = collectionRepository,
            addBooksToShelfUseCase = addBooksToShelfUseCase,
            createShelfUseCase = mock(),
            errorBus = errorBus,
            bookAvailability =
                object : BookAvailability {
                    override fun observe(bookId: BookId): Flow<BookAvailability.State> = availability
                },
            serverReachability =
                object : ServerReachability {
                    override val state = MutableStateFlow<Reachability>(Reachability.Unknown)

                    override suspend fun retry() = Unit
                },
            documentRepository = documentRepository,
            inboxRepository = inbox,
            bookVisibilityRepository = visibility,
            bookEditRepository = bookEditRepository,
            seriesRepository = seriesRepository,
            undoMatch = mock(),
            userProfileRepository = mock(),
            loadedBookRepository = FakeLoadedBookRepository(loadedBookId),
            playhead = FakeLoadedBookPlayhead(playhead),
        )
}

/** "Now", for writes that stamp a time. */
internal const val FIXTURE_NOW_MS = 1_700_000_000_000L

/** A saved position [ms] into the fixture's book. */
internal fun positionOf(
    ms: Long,
    finished: Boolean = false,
) = PlaybackPosition(
    bookId = "book-1",
    positionMs = ms,
    playbackSpeed = 1f,
    hasCustomSpeed = false,
    volumeBoostDb = 0f,
    hasCustomBoost = false,
    measuredGainDb = null,
    updatedAtMs = 0L,
    syncedAtMs = null,
    lastPlayedAtMs = 0L,
    isFinished = finished,
)

/**
 * Keeps [vm]'s `state` collected for the rest of the test, the way an open screen does. `state` runs only while
 * something watches it, and an action acts on the book the screen is showing — so a spec that reads
 * `state.value` or calls an action must watch first.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.watch(vm: BookDetailViewModel): BookDetailViewModel {
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
    return vm
}
