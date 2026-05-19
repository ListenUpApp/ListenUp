package com.calypsan.listenup.client.test.fake

import app.cash.turbine.test
import com.calypsan.listenup.client.TestData
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.domain.model.Chapter
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.calypsan.listenup.core.Success

class FakeBookRepositoryTest {
    @Test
    fun observeBookListItemsEmitsSeededThenUpdated() =
        runTest {
            val first = TestData.bookListItem(id = "book-1", title = "Dune")
            val repo = FakeBookRepository(initialBooks = listOf(first))

            repo.observeBookListItems().test {
                assertEquals(listOf(first), awaitItem())

                val second = TestData.bookListItem(id = "book-2", title = "Neuromancer")
                repo.setBooks(listOf(first, second))

                assertEquals(2, awaitItem().size)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun getBookListItemLooksUpById() =
        runTest {
            val book = TestData.bookListItem(id = "book-1", title = "Dune")
            val repo = FakeBookRepository(initialBooks = listOf(book))

            val fetched = repo.getBookListItem("book-1")
            val missing = repo.getBookListItem("nope")

            assertNotNull(fetched)
            assertEquals("Dune", fetched.title)
            assertNull(missing)
        }

    @Test
    fun getBookListItemsReturnsOnlyMatchingIds() =
        runTest {
            val a = TestData.bookListItem(id = "a", title = "A")
            val b = TestData.bookListItem(id = "b", title = "B")
            val c = TestData.bookListItem(id = "c", title = "C")
            val repo = FakeBookRepository(initialBooks = listOf(a, b, c))

            val found = repo.getBookListItems(listOf("a", "c", "missing"))

            assertEquals(2, found.size)
            assertTrue(found.any { it.id == BookId("a") })
            assertTrue(found.any { it.id == BookId("c") })
        }

    @Test
    fun refreshBooksSucceedsAndCountsCalls() =
        runTest {
            val repo = FakeBookRepository()

            val result = repo.refreshBooks()

            assertTrue(result is Success)
            assertEquals(1, repo.refreshCount)
        }

    @Test
    fun chaptersRoundTripThroughSetChapters() =
        runTest {
            val repo = FakeBookRepository()
            val chapters = listOf(Chapter(id = "c1", title = "Ch 1", duration = 1_000L, startTime = 0L))

            repo.setChapters("book-1", chapters)

            assertEquals(chapters, repo.getChapters("book-1"))
        }

    @Test
    fun observeRecentlyAddedBooksOrdersByAddedAtDescending() =
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
                assertEquals(2, first.size)
                assertEquals("newer", first[0].id)
                assertEquals("older", first[1].id)
                cancelAndIgnoreRemainingEvents()
            }
        }
}
