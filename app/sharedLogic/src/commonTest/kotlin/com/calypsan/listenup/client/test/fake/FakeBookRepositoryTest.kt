package com.calypsan.listenup.client.test.fake

import com.calypsan.listenup.api.result.AppResult
import app.cash.turbine.test
import com.calypsan.listenup.client.TestData
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.domain.model.Chapter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class FakeBookRepositoryTest :
    FunSpec({
        test("observeBookListItemsEmitsSeededThenUpdated") {
            runTest {
                val first = TestData.bookListItem(id = "book-1", title = "Dune")
                val repo = FakeBookRepository(initialBooks = listOf(first))

                repo.observeBookListItems().test {
                    awaitItem() shouldBe listOf(first)

                    val second = TestData.bookListItem(id = "book-2", title = "Neuromancer")
                    repo.setBooks(listOf(first, second))

                    awaitItem().size shouldBe 2
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("getBookListItemLooksUpById") {
            runTest {
                val book = TestData.bookListItem(id = "book-1", title = "Dune")
                val repo = FakeBookRepository(initialBooks = listOf(book))

                val fetched = repo.getBookListItem("book-1")
                val missing = repo.getBookListItem("nope")

                fetched.shouldNotBeNull()
                fetched.title shouldBe "Dune"
                missing shouldBe null
            }
        }

        test("getBookListItemsReturnsOnlyMatchingIds") {
            runTest {
                val a = TestData.bookListItem(id = "a", title = "A")
                val b = TestData.bookListItem(id = "b", title = "B")
                val c = TestData.bookListItem(id = "c", title = "C")
                val repo = FakeBookRepository(initialBooks = listOf(a, b, c))

                val found = repo.getBookListItems(listOf("a", "c", "missing"))

                found.size shouldBe 2
                found.any { it.id == BookId("a") } shouldBe true
                found.any { it.id == BookId("c") } shouldBe true
            }
        }

        test("refreshBooksSucceedsAndCountsCalls") {
            runTest {
                val repo = FakeBookRepository()

                val result = repo.refreshBooks()

                (result is AppResult.Success) shouldBe true
                repo.refreshCount shouldBe 1
            }
        }

        test("chaptersRoundTripThroughSetChapters") {
            runTest {
                val repo = FakeBookRepository()
                val chapters = listOf(Chapter(id = "c1", title = "Ch 1", duration = 1_000L, startTime = 0L))

                repo.setChapters("book-1", chapters)

                repo.getChapters("book-1") shouldBe chapters
            }
        }

        test("observeRecentlyAddedBooksOrdersByAddedAtDescending") {
            runTest {
                val older =
                    TestData.bookListItem(id = "older").copy(
                        addedAt =
                            com.calypsan.listenup.core
                                .Timestamp(epochMillis = 1_000L),
                    )
                val newer =
                    TestData.bookListItem(id = "newer").copy(
                        addedAt =
                            com.calypsan.listenup.core
                                .Timestamp(epochMillis = 2_000L),
                    )
                val repo = FakeBookRepository(initialBooks = listOf(older, newer))

                repo.observeRecentlyAddedBooks(limit = 10).test {
                    val first = awaitItem()
                    first.size shouldBe 2
                    first[0].id shouldBe "newer"
                    first[1].id shouldBe "older"
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })
