package com.calypsan.listenup.client.presentation.readingorder

import app.cash.turbine.test
import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.ReadingOrder
import com.calypsan.listenup.client.domain.model.Series
import com.calypsan.listenup.client.domain.model.SeriesWithBooks
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import com.calypsan.listenup.client.test.fake.FakeBookRepository
import com.calypsan.listenup.client.test.fake.FakeReadingOrderRepository
import com.calypsan.listenup.client.test.fake.FakeSeriesRepository
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.ReadingOrderId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.core.error.ErrorBus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Tests for [ReadingOrderDetailViewModel]. Pins junction-order preservation over the book
 * repository's own emission order, owner-gating on [ReadingOrderDetailViewModel.reorder],
 * the series-sequence-ordered [ReadingOrderDetailUiState.Ready.addableBooks] pool, the
 * offline-capable [ReadingOrderDetailUiState.Ready.isOwner] check, [ReadingOrderDetailUiState.NotFound],
 * and the delete/clear-active one-shot paths.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadingOrderDetailViewModelTest :
    FunSpec({
        val testDispatcher = StandardTestDispatcher()

        beforeTest { Dispatchers.setMain(testDispatcher) }
        afterTest { Dispatchers.resetMain() }

        fun order(
            id: String,
            ownerId: String = "u1",
        ) = ReadingOrder(
            id = ReadingOrderId(id),
            name = "Order $id",
            description = null,
            attribution = "",
            isPrivate = false,
            ownerId = ownerId,
            ownerDisplayName = "Owner",
            bookCount = 0,
            totalDurationSeconds = 0,
            createdAtMs = 0L,
            updatedAtMs = 0L,
        )

        fun book(
            id: String,
            title: String = "Book $id",
        ) = BookListItem(
            id = BookId(id),
            libraryId = LibraryId("lib-1"),
            folderId = FolderId("folder-1"),
            title = title,
            authors = listOf(BookContributor(id = "author-1", name = "Brandon Sanderson")),
            narrators = emptyList(),
            duration = 3_600_000L,
            coverPath = null,
            addedAt = Timestamp(0L),
            updatedAt = Timestamp(0L),
        )

        fun series(id: String = "series-1") = Series(id = SeriesId(id), name = "The Stormlight Archive", createdAt = Timestamp(0L))

        /** Fixture wiring every [ReadingOrderDetailViewModel] dependency as an in-memory fake. */
        class Fixture(
            userId: String? = "u1",
        ) {
            val readingOrderRepo = FakeReadingOrderRepository()
            val bookRepo = FakeBookRepository()
            val seriesRepo = FakeSeriesRepository()
            val authSession = FakeAuthSession(userId = userId)
            val errorBus = ErrorBus()

            fun build() = ReadingOrderDetailViewModel(readingOrderRepo, bookRepo, seriesRepo, authSession, errorBus)
        }

        test("junction order is preserved even when the book repository emits in a different order") {
            runTest {
                val fixture = Fixture()
                fixture.readingOrderRepo.setMyOrders(listOf(order("ro1")))
                fixture.readingOrderRepo.setBookIds(ReadingOrderId("ro1"), listOf(BookId("b2"), BookId("b1"), BookId("b3")))
                fixture.bookRepo.setBooks(listOf(book("b1"), book("b3"), book("b2")))
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe ReadingOrderDetailUiState.Idle
                    viewModel.load(ReadingOrderId("ro1"), "series-1")
                    advanceUntilIdle()
                    awaitItem() shouldBe ReadingOrderDetailUiState.Loading

                    val ready = awaitItem().shouldBeInstanceOf<ReadingOrderDetailUiState.Ready>()
                    ready.books.map { it.bookId } shouldContainExactly listOf("b2", "b1", "b3")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("reorder is recorded when the caller owns the order") {
            runTest {
                val fixture = Fixture(userId = "u1")
                fixture.readingOrderRepo.setMyOrders(listOf(order("ro1", ownerId = "u1")))
                fixture.readingOrderRepo.setBookIds(ReadingOrderId("ro1"), listOf(BookId("b1"), BookId("b2")))
                fixture.bookRepo.setBooks(listOf(book("b1"), book("b2")))
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe ReadingOrderDetailUiState.Idle
                    viewModel.load(ReadingOrderId("ro1"), "series-1")
                    advanceUntilIdle()
                    awaitItem() shouldBe ReadingOrderDetailUiState.Loading
                    awaitItem().shouldBeInstanceOf<ReadingOrderDetailUiState.Ready>().isOwner shouldBe true

                    viewModel.reorder(listOf("b2", "b1"))
                    advanceUntilIdle()

                    fixture.readingOrderRepo.reorderBooksCalls shouldContainExactly
                        listOf(
                            FakeReadingOrderRepository.ReorderBooksCall(
                                id = ReadingOrderId("ro1"),
                                orderedBookIds = listOf(BookId("b2"), BookId("b1")),
                            ),
                        )
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("reorder is a no-op for a non-owner") {
            runTest {
                val fixture = Fixture(userId = "u2")
                fixture.readingOrderRepo.setMyOrders(listOf(order("ro1", ownerId = "u1")))
                fixture.readingOrderRepo.setBookIds(ReadingOrderId("ro1"), listOf(BookId("b1"), BookId("b2")))
                fixture.bookRepo.setBooks(listOf(book("b1"), book("b2")))
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe ReadingOrderDetailUiState.Idle
                    viewModel.load(ReadingOrderId("ro1"), "series-1")
                    advanceUntilIdle()
                    awaitItem() shouldBe ReadingOrderDetailUiState.Loading
                    awaitItem().shouldBeInstanceOf<ReadingOrderDetailUiState.Ready>().isOwner shouldBe false

                    viewModel.reorder(listOf("b2", "b1"))
                    advanceUntilIdle()

                    fixture.readingOrderRepo.reorderBooksCalls shouldBe emptyList()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("addableBooks excludes current members and follows series sequence order") {
            runTest {
                val fixture = Fixture()
                fixture.readingOrderRepo.setMyOrders(listOf(order("ro1")))
                fixture.readingOrderRepo.setBookIds(ReadingOrderId("ro1"), listOf(BookId("b1")))
                fixture.bookRepo.setBooks(listOf(book("b1"), book("b2"), book("b3")))
                fixture.seriesRepo.setSeriesWithBooks(
                    "series-1",
                    SeriesWithBooks(
                        series = series(),
                        // Deliberately shuffled — sequence order must win, not list order.
                        books = listOf(book("b3"), book("b1"), book("b2")),
                        bookSequences = mapOf("b1" to "1", "b2" to "2", "b3" to "3"),
                    ),
                )
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe ReadingOrderDetailUiState.Idle
                    viewModel.load(ReadingOrderId("ro1"), "series-1")
                    advanceUntilIdle()
                    awaitItem() shouldBe ReadingOrderDetailUiState.Loading

                    val ready = awaitItem().shouldBeInstanceOf<ReadingOrderDetailUiState.Ready>()
                    ready.addableBooks.map { it.bookId } shouldContainExactly listOf("b2", "b3")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("isOwner is true only when authSession's user id matches the order's owner") {
            runTest {
                val fixture = Fixture(userId = "u1")
                fixture.readingOrderRepo.setMyOrders(listOf(order("ro1", ownerId = "u1")))
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe ReadingOrderDetailUiState.Idle
                    viewModel.load(ReadingOrderId("ro1"), "series-1")
                    advanceUntilIdle()
                    awaitItem() shouldBe ReadingOrderDetailUiState.Loading
                    awaitItem().shouldBeInstanceOf<ReadingOrderDetailUiState.Ready>().isOwner shouldBe true
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("an order absent from the local mirror surfaces as NotFound") {
            runTest {
                val fixture = Fixture()
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe ReadingOrderDetailUiState.Idle
                    viewModel.load(ReadingOrderId("missing"), "series-1")
                    advanceUntilIdle()
                    awaitItem() shouldBe ReadingOrderDetailUiState.Loading
                    awaitItem() shouldBe ReadingOrderDetailUiState.NotFound
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("deleteOrder success emits Deleted") {
            runTest {
                val fixture = Fixture()
                fixture.readingOrderRepo.setMyOrders(listOf(order("ro1")))
                val viewModel = fixture.build()
                viewModel.load(ReadingOrderId("ro1"), "series-1")

                viewModel.events.test {
                    viewModel.deleteOrder()
                    advanceUntilIdle()
                    awaitItem() shouldBe ReadingOrderDetailEvent.Deleted
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("clearActive records a null follow for the loaded series") {
            runTest {
                val fixture = Fixture()
                fixture.readingOrderRepo.setMyOrders(listOf(order("ro1")))
                fixture.readingOrderRepo.setActiveOrder("series-1", ReadingOrderId("ro1"))
                val viewModel = fixture.build()
                viewModel.load(ReadingOrderId("ro1"), "series-1")

                viewModel.clearActive()
                advanceUntilIdle()

                fixture.readingOrderRepo.setActiveReadingOrderCalls shouldContainExactly
                    listOf(FakeReadingOrderRepository.SetActiveCall("series-1", null))
            }
        }
    })
