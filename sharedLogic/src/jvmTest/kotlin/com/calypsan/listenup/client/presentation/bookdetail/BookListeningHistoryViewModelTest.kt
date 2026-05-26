package com.calypsan.listenup.client.presentation.bookdetail

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.calypsan.listenup.client.domain.history.BookListeningHistory
import com.calypsan.listenup.client.domain.history.DayBucket
import com.calypsan.listenup.client.domain.repository.BookListeningHistoryRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate

class BookListeningHistoryViewModelTest :
    FunSpec({
        test("initial state is Loading") {
            runTest {
                val repo = stubRepo(MutableSharedFlow())
                val vm = BookListeningHistoryViewModel(repo, bookId = "bookA")
                vm.uiState.test {
                    awaitItem() shouldBe BookListeningHistoryUiState.Loading
                }
            }
        }

        test("when repo emits empty history, state transitions to Empty") {
            runTest {
                val repo = stubRepo(MutableStateFlow(BookListeningHistory(daily = emptyList())))
                val vm = BookListeningHistoryViewModel(repo, bookId = "bookA")
                vm.uiState.test {
                    awaitUntil { it is BookListeningHistoryUiState.Empty }
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("when repo emits non-empty history, state transitions to Data") {
            runTest {
                val history =
                    BookListeningHistory(
                        daily =
                            listOf(
                                DayBucket(
                                    date = LocalDate(2026, 5, 22),
                                    relativeLabel = "Today",
                                    totalSeconds = 3600L,
                                    events = emptyList(),
                                ),
                            ),
                    )
                val repo = stubRepo(MutableStateFlow(history))
                val vm = BookListeningHistoryViewModel(repo, bookId = "bookA")
                vm.uiState.test {
                    val data =
                        awaitUntil { it is BookListeningHistoryUiState.Data }
                            .shouldBeInstanceOf<BookListeningHistoryUiState.Data>()
                    data.history shouldBe history
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("when repo throws (non-cancellation), state transitions to Error") {
            runTest {
                val repo =
                    object : BookListeningHistoryRepository {
                        override fun observeFor(bookId: String): Flow<BookListeningHistory> =
                            flow {
                                throw RuntimeException("boom")
                            }
                    }
                val vm = BookListeningHistoryViewModel(repo, bookId = "bookA")
                vm.uiState.test {
                    val error =
                        awaitUntil { it is BookListeningHistoryUiState.Error }
                            .shouldBeInstanceOf<BookListeningHistoryUiState.Error>()
                    error.isRetryable shouldBe true
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })

private fun stubRepo(events: Flow<BookListeningHistory>): BookListeningHistoryRepository =
    object : BookListeningHistoryRepository {
        override fun observeFor(bookId: String): Flow<BookListeningHistory> = events
    }

/**
 * Drains items from this Turbine until [predicate] matches, then returns the matching item.
 * Robust against the `stateIn(WhileSubscribed, initial)` collapse race where the initial
 * state may or may not emit separately from the first upstream value under CI parallelism.
 *
 * Per memory `test-stateflow-use-mutablestateflow` Rule 2.
 */
private suspend fun <T> ReceiveTurbine<T>.awaitUntil(predicate: (T) -> Boolean): T {
    while (true) {
        val item = awaitItem()
        if (predicate(item)) return item
    }
}
