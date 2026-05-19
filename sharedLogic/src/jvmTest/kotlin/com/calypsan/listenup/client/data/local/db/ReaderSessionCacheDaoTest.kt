package com.calypsan.listenup.client.data.local.db

import app.cash.turbine.test
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies [ReaderSessionCacheDao] against a real in-memory [ListenUpDatabase].
 *
 * Covers the exclude-user filter, ordering (currently-reading first, then most-recent),
 * delete-by-book scoping, and FK CASCADE on book deletion.
 */
class ReaderSessionCacheDaoTest {
    private val db: ListenUpDatabase = createInMemoryTestDatabase()
    private val bookDao = db.bookDao()
    private val cacheDao = db.readerSessionCacheDao()

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private suspend fun seedBook(id: String = "b1") {
        bookDao.upsert(
            BookEntity(
                id = BookId(id),
                title = "Test $id",
                sortTitle = "Test $id",
                subtitle = null,
                coverHash = null,
                coverBlurHash = null,
                dominantColor = null,
                darkMutedColor = null,
                vibrantColor = null,
                totalDuration = 0L,
                description = null,
                publishYear = null,
                publisher = null,
                language = null,
                isbn = null,
                asin = null,
                abridged = false,
                createdAt = Timestamp(1L),
                updatedAt = Timestamp(1L),
            ),
        )
    }

    private fun cacheEntry(
        bookId: String = "b1",
        userId: String,
        displayName: String = "User $userId",
        isCurrentlyReading: Boolean = false,
        updatedAt: Long = 1L,
    ): ReaderSessionCacheEntity =
        ReaderSessionCacheEntity(
            bookId = bookId,
            userId = userId,
            userDisplayName = displayName,
            userAvatarColor = "#FF0000",
            userAvatarType = "auto",
            userAvatarValue = null,
            isCurrentlyReading = isCurrentlyReading,
            currentProgress = 0.5,
            startedAt = 1_000L,
            finishedAt = null,
            completionCount = 0,
            updatedAt = updatedAt,
        )

    @Test
    fun `observeForBook excludes the current user and orders by active then recent`() =
        runTest {
            seedBook()
            cacheDao.upsertAll(
                listOf(
                    cacheEntry(userId = "u1", isCurrentlyReading = false, updatedAt = 5L),
                    cacheEntry(userId = "u2", isCurrentlyReading = true, updatedAt = 2L),
                    cacheEntry(userId = "me", isCurrentlyReading = true, updatedAt = 10L),
                ),
            )

            val result = cacheDao.getForBook(bookId = "b1", excludingUserId = "me")

            assertEquals(listOf("u2", "u1"), result.map { it.userId })
        }

    @Test
    fun `observeForBook with empty excludingUserId returns all readers`() =
        runTest {
            seedBook()
            cacheDao.upsertAll(
                listOf(
                    cacheEntry(userId = "u1", updatedAt = 1L),
                    cacheEntry(userId = "u2", updatedAt = 2L),
                ),
            )

            val result = cacheDao.getForBook(bookId = "b1", excludingUserId = "")

            assertEquals(setOf("u1", "u2"), result.map { it.userId }.toSet())
        }

    @Test
    fun `observeForBook emits initial list and re-emits on upsert`() =
        runTest {
            seedBook()
            cacheDao.upsertAll(listOf(cacheEntry(userId = "u1", updatedAt = 1L)))

            cacheDao.observeForBook(bookId = "b1", excludingUserId = "me").test {
                assertEquals(listOf("u1"), awaitItem().map { it.userId })

                cacheDao.upsertAll(listOf(cacheEntry(userId = "u2", updatedAt = 2L)))
                val emission = awaitItem()
                assertEquals(setOf("u1", "u2"), emission.map { it.userId }.toSet())

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `deleteForBook removes only that book's cache rows`() =
        runTest {
            seedBook(id = "b1")
            seedBook(id = "b2")
            cacheDao.upsertAll(
                listOf(
                    cacheEntry(bookId = "b1", userId = "u1"),
                    cacheEntry(bookId = "b2", userId = "u1"),
                ),
            )

            cacheDao.deleteForBook(bookId = "b1")

            assertTrue(cacheDao.getForBook("b1", "me").isEmpty())
            assertEquals(listOf("u1"), cacheDao.getForBook("b2", "me").map { it.userId })
        }

    @Test
    fun `cascade delete removes cache rows when book is deleted`() =
        runTest {
            seedBook(id = "b1")
            cacheDao.upsertAll(
                listOf(
                    cacheEntry(bookId = "b1", userId = "u1"),
                    cacheEntry(bookId = "b1", userId = "u2"),
                ),
            )

            bookDao.deleteById(BookId("b1"))

            assertTrue(cacheDao.getForBook("b1", "me").isEmpty())
        }
}
