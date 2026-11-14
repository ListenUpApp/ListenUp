package com.calypsan.listenup.client.presentation.bookdetail

import com.calypsan.listenup.client.test.fake.FakeAuthSession
import com.calypsan.listenup.client.test.fake.FakeBookRepository
import com.calypsan.listenup.client.test.fake.FakeLoadedBookRepository
import com.calypsan.listenup.client.test.fake.FakeServerReachability
import app.cash.turbine.test
import com.calypsan.listenup.client.domain.readers.BookReaders
import com.calypsan.listenup.client.domain.readers.Reader
import com.calypsan.listenup.client.domain.readers.ReaderLineKind
import com.calypsan.listenup.client.domain.model.ActiveSession
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.model.LoadedBook
import com.calypsan.listenup.client.domain.repository.ActiveSessionRepository
import com.calypsan.listenup.client.domain.repository.Reachability
import com.calypsan.listenup.client.domain.repository.BookReadersRepository
import com.calypsan.listenup.client.test.SimulatedFailure
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Tests for [BookReadersViewModel]. Pins the sealed [BookReadersUiState] mapping over
 * [BookReadersRepository.observeReadersFor]: Loading first, empty readers map to [BookReadersUiState.NoReaders],
 * a non-empty list maps to [BookReadersUiState.Data], and an upstream flow failure maps to
 * [BookReadersUiState.Error] (via [com.calypsan.listenup.client.core.fallbackTo]) rather than propagating.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookReadersViewModelTest :
    FunSpec({
        val testDispatcher = StandardTestDispatcher()

        beforeTest { Dispatchers.setMain(testDispatcher) }
        afterTest { Dispatchers.resetMain() }

        fun reader(
            userId: String = "u1",
            displayName: String = "Bob",
            isYou: Boolean = false,
            currentProgressPct: Int? = null,
            finishes: List<Long> = emptyList(),
        ) = Reader(
            userId = userId,
            displayName = displayName,
            isYou = isYou,
            currentProgressPct = currentProgressPct,
            finishes = finishes,
        )

        fun fakeRepo(flow: Flow<BookReaders>): BookReadersRepository =
            object : BookReadersRepository {
                override fun observeReadersFor(bookId: String): Flow<BookReaders> = flow
            }

        /** Presence for every book: the roster [ActiveSessionRepository] would hand Discover. */
        class FakePresence(
            val sessions: MutableStateFlow<List<ActiveSession>> = MutableStateFlow(emptyList()),
            val failure: Throwable? = null,
        ) : ActiveSessionRepository {
            override fun observeActiveSessions(currentUserId: String): Flow<List<ActiveSession>> =
                failure?.let { error -> flow { throw error } } ?: sessions

            override fun observeActiveCount(currentUserId: String): Flow<Int> = flowOf(sessions.value.size)
        }

        fun session(
            userId: String,
            bookId: String = "b1",
            isLive: Boolean = true,
        ) = ActiveSession(
            sessionId = "$userId:$bookId",
            userId = userId,
            bookId = bookId,
            lastActiveAtMs = 1L,
            isLive = isLive,
            observedAtMs = 1L,
            user = ActiveSession.SessionUser(userId, "auto", null, "#000000"),
            book = ActiveSession.SessionBook(bookId, "Title", null, null, null),
        )

        fun viewModel(
            readers: Flow<BookReaders>,
            loadedBooks: FakeLoadedBookRepository = FakeLoadedBookRepository(),
            chapters: List<Chapter> = emptyList(),
            presence: ActiveSessionRepository = FakePresence(),
            reachability: FakeServerReachability = FakeServerReachability(),
        ) = BookReadersViewModel(
            repo = fakeRepo(readers),
            bookId = "b1",
            loadedBooks = loadedBooks,
            bookRepository = FakeBookRepository(initialChapters = mapOf("b1" to chapters)),
            activeSessions = presence,
            authSession = FakeAuthSession(userId = "me"),
            serverReachability = reachability,
        )

        val twoChapters =
            listOf(
                Chapter(id = "c1", title = "One", duration = 1_000L, startTime = 0L),
                Chapter(id = "c2", title = "Two", duration = 1_000L, startTime = 1_000L),
            )

        test("initial state is Loading") {
            runTest {
                val viewModel = viewModel(flowOf(BookReaders(emptyList())))

                viewModel.uiState.test {
                    awaitItem() shouldBe BookReadersUiState.Loading
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("empty readers maps to NoReaders") {
            runTest {
                val viewModel = viewModel(flowOf(BookReaders(emptyList())))

                viewModel.uiState.test {
                    awaitItem() shouldBe BookReadersUiState.Loading
                    awaitItem() shouldBe BookReadersUiState.NoReaders
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("non-empty readers maps to Data") {
            runTest {
                val readers = BookReaders(listOf(reader(userId = "u2", displayName = "Jake")))
                val viewModel = viewModel(flowOf(readers))

                viewModel.uiState.test {
                    awaitItem() shouldBe BookReadersUiState.Loading
                    val item = awaitItem()
                    item shouldBe BookReadersUiState.Data(readers)
                    (item as BookReadersUiState.Data).summary shouldBe item.readers.summary()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("upstream flow failure maps to Error(isRetryable = true)") {
            runTest {
                val failingFlow: Flow<BookReaders> = flow { throw SimulatedFailure("room blew up") }
                val viewModel = viewModel(failingFlow)

                viewModel.uiState.test {
                    awaitItem() shouldBe BookReadersUiState.Loading
                    awaitItem() shouldBe BookReadersUiState.Error(isRetryable = true)
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("you are live only while the player is playing this book") {
            runTest {
                val readers = BookReaders(listOf(reader(userId = "me", isYou = true, currentProgressPct = 40)))
                val player = FakeLoadedBookRepository()
                val viewModel = viewModel(flowOf(readers), loadedBooks = player)

                viewModel.uiState.test {
                    awaitItem() shouldBe BookReadersUiState.Loading
                    (awaitItem() as BookReadersUiState.Data).youAreLive shouldBe false

                    player.loaded.value = LoadedBook.Loaded(bookId = "other", isPlaying = true, isFinished = false)
                    expectNoEvents()

                    player.loaded.value = LoadedBook.Loaded(bookId = "b1", isPlaying = false, isFinished = false)
                    expectNoEvents()

                    player.loaded.value = LoadedBook.Loaded(bookId = "b1", isPlaying = true, isFinished = false)
                    val live = awaitItem() as BookReadersUiState.Data
                    live.youAreLive shouldBe true
                    live.lines.first().kind shouldBe ReaderLineKind.Reading(40, isLive = true)
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("another reader is live while fresh presence says they are listening to this book") {
            runTest {
                val readers =
                    BookReaders(
                        listOf(
                            reader(userId = "maya", displayName = "Maya", currentProgressPct = 20),
                            reader(userId = "tom", displayName = "Tom", currentProgressPct = 60),
                        ),
                    )
                val presence =
                    FakePresence(
                        MutableStateFlow(listOf(session("maya"), session("tom", bookId = "other"), session("x", isLive = false))),
                    )
                val reachability = FakeServerReachability().apply { state.value = Reachability.Reachable }
                val viewModel = viewModel(flowOf(readers), presence = presence, reachability = reachability)

                viewModel.uiState.test {
                    awaitItem() shouldBe BookReadersUiState.Loading
                    var data = awaitItem() as BookReadersUiState.Data
                    while (data.liveUserIds.isEmpty()) data = awaitItem() as BookReadersUiState.Data
                    data.liveUserIds shouldBe setOf("maya")
                    data.isLive(readers.readers[0]) shouldBe true
                    data.isLive(readers.readers[1]) shouldBe false
                    data.lines.first { it.userId == "maya" }.kind shouldBe ReaderLineKind.Reading(20, isLive = true)

                    // A known-down server dates the roster: nobody is claimed live from it.
                    reachability.state.value = Reachability.Unreachable
                    (awaitItem() as BookReadersUiState.Data).liveUserIds shouldBe emptySet()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("presence failing leaves the readers showing, with nobody live") {
            runTest {
                val readers = BookReaders(listOf(reader(userId = "maya", currentProgressPct = 20)))
                val viewModel =
                    viewModel(flowOf(readers), presence = FakePresence(failure = SimulatedFailure("presence down")))

                viewModel.uiState.test {
                    awaitItem() shouldBe BookReadersUiState.Loading
                    val data = awaitItem() as BookReadersUiState.Data
                    data.liveUserIds shouldBe emptySet()
                    expectNoEvents()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("the chapter list carries each other reader on their chapter, live ones ringed") {
            runTest {
                val readers =
                    BookReaders(
                        listOf(
                            reader(userId = "me", isYou = true, currentProgressPct = 10),
                            Reader(
                                userId = "maya",
                                displayName = "Maya",
                                isYou = false,
                                currentProgressPct = 75,
                                finishes = emptyList(),
                                currentPositionMs = 1_500L,
                            ),
                        ),
                    )
                val presence = FakePresence(MutableStateFlow(listOf(session("maya"))))
                val viewModel = viewModel(flowOf(readers), chapters = twoChapters, presence = presence)

                viewModel.uiState.test {
                    awaitItem() shouldBe BookReadersUiState.Loading
                    var data = awaitItem() as BookReadersUiState.Data
                    while (data.liveUserIds.isEmpty()) data = awaitItem() as BookReadersUiState.Data
                    data.chapterReaders.onChapter("c1").shouldBeNull()
                    data.chapterReaders
                        .onChapter("c2")
                        .shouldNotBeNull()
                        .faces shouldBe
                        listOf(ChapterReaderFace("maya", "Maya", isLive = true))
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })
