package com.calypsan.listenup.client.presentation.bookdetail

import app.cash.turbine.test
import com.calypsan.listenup.client.domain.history.BookListeningHistory
import com.calypsan.listenup.client.domain.history.DayBucket
import com.calypsan.listenup.client.domain.repository.BookListeningHistoryRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
                val repo = stubRepo(flowOf(BookListeningHistory(daily = emptyList())))
                val vm = BookListeningHistoryViewModel(repo, bookId = "bookA")
                vm.uiState.test {
                    awaitItem() shouldBe BookListeningHistoryUiState.Loading
                    awaitItem() shouldBe BookListeningHistoryUiState.Empty
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
                val repo = stubRepo(flowOf(history))
                val vm = BookListeningHistoryViewModel(repo, bookId = "bookA")
                vm.uiState.test {
                    awaitItem() shouldBe BookListeningHistoryUiState.Loading
                    val data = awaitItem()
                    data.shouldBeInstanceOf<BookListeningHistoryUiState.Data>()
                    data.history shouldBe history
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
                    awaitItem() shouldBe BookListeningHistoryUiState.Loading
                    val error = awaitItem()
                    error.shouldBeInstanceOf<BookListeningHistoryUiState.Error>()
                    error.isRetryable shouldBe true
                }
            }
        }
    })

private fun stubRepo(events: Flow<BookListeningHistory>): BookListeningHistoryRepository =
    object : BookListeningHistoryRepository {
        override fun observeFor(bookId: String): Flow<BookListeningHistory> = events
    }
