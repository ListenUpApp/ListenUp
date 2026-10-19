package com.calypsan.listenup.client.presentation.bookdetail

import app.cash.turbine.test
import com.calypsan.listenup.client.domain.readers.BookReaders
import com.calypsan.listenup.client.domain.readers.Reader
import com.calypsan.listenup.client.domain.readers.ReaderLineKind
import com.calypsan.listenup.client.domain.model.LoadedBook
import com.calypsan.listenup.client.domain.repository.BookReadersRepository
import com.calypsan.listenup.client.test.SimulatedFailure
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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

        test("initial state is Loading") {
            runTest {
                val viewModel =
                    BookReadersViewModel(
                        fakeRepo(flowOf(BookReaders(emptyList()))),
                        bookId = "b1",
                        loadedBooks = FakeLoadedBookRepository(),
                    )

                viewModel.uiState.test {
                    awaitItem() shouldBe BookReadersUiState.Loading
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("empty readers maps to NoReaders") {
            runTest {
                val viewModel =
                    BookReadersViewModel(
                        fakeRepo(flowOf(BookReaders(emptyList()))),
                        bookId = "b1",
                        loadedBooks = FakeLoadedBookRepository(),
                    )

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
                val viewModel = BookReadersViewModel(fakeRepo(flowOf(readers)), bookId = "b1", loadedBooks = FakeLoadedBookRepository())

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
                val viewModel = BookReadersViewModel(fakeRepo(failingFlow), bookId = "b1", loadedBooks = FakeLoadedBookRepository())

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
                val viewModel = BookReadersViewModel(fakeRepo(flowOf(readers)), bookId = "b1", loadedBooks = player)

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
    })
