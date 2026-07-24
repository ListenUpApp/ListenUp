package com.calypsan.listenup.client.presentation.bookdetail

import app.cash.turbine.test
import com.calypsan.listenup.client.domain.readers.BookReaders
import com.calypsan.listenup.client.domain.readers.Reader
import com.calypsan.listenup.client.domain.repository.BookReadersRepository
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
                val viewModel = BookReadersViewModel(fakeRepo(flowOf(BookReaders(emptyList()))), bookId = "b1")

                viewModel.uiState.test {
                    awaitItem() shouldBe BookReadersUiState.Loading
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("empty readers maps to NoReaders") {
            runTest {
                val viewModel = BookReadersViewModel(fakeRepo(flowOf(BookReaders(emptyList()))), bookId = "b1")

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
                val viewModel = BookReadersViewModel(fakeRepo(flowOf(readers)), bookId = "b1")

                viewModel.uiState.test {
                    awaitItem() shouldBe BookReadersUiState.Loading
                    awaitItem() shouldBe BookReadersUiState.Data(readers)
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("upstream flow failure maps to Error(isRetryable = true)") {
            runTest {
                val failingFlow: Flow<BookReaders> = flow { throw RuntimeException("room blew up") }
                val viewModel = BookReadersViewModel(fakeRepo(failingFlow), bookId = "b1")

                viewModel.uiState.test {
                    awaitItem() shouldBe BookReadersUiState.Loading
                    awaitItem() shouldBe BookReadersUiState.Error(isRetryable = true)
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })
