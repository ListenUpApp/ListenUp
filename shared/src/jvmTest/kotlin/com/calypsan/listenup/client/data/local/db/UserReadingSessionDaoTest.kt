package com.calypsan.listenup.client.data.local.db

import app.cash.turbine.test
import com.calypsan.listenup.client.core.BookId
import com.calypsan.listenup.client.core.Timestamp
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies [UserReadingSessionDao] against a real in-memory [ListenUpDatabase].
 *
 * Covers the DAO contract BookPuller/SessionRepositoryImpl rely on: observe-by-book+user
 * ordering, delete-by-book+user scoping, and FK CASCADE on book deletion.
 */
class UserReadingSessionDaoTest {
    private val db: ListenUpDatabase = createInMemoryTestDatabase()
    private val bookDao = db.bookDao()
    private val userSessionDao = db.userReadingSessionDao()

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private suspend fun seedBook(
        id: String = "b1",
        title: String = "Test Book",
    ) {
        bookDao.upsert(
            BookEntity(
                id = BookId(id),
                title = title,
                sortTitle = title,
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

    private fun session(
        id: String,
        bookId: String = "b1",
        userId: String = "u1",
        startedAt: Long = 1_000L,
        finishedAt: Long? = null,
        isCompleted: Boolean = false,
        listenTimeMs: Long = 0L,
    ): UserReadingSessionEntity =
        UserReadingSessionEntity(
            id = id,
            bookId = bookId,
            userId = userId,
            startedAt = startedAt,
            finishedAt = finishedAt,
            isCompleted = isCompleted,
            listenTimeMs = listenTimeMs,
            updatedAt = 1L,
        )

    @Test
    fun `upsertAll and getForBook returns sessions ordered by startedAt DESC`() =
        runTest {
            seedBook()
            userSessionDao.upsertAll(
                listOf(
                    session(id = "s1", startedAt = 1_000L),
                    session(id = "s3", startedAt = 3_000L),
                    session(id = "s2", startedAt = 2_000L),
                ),
            )

            val result = userSessionDao.getForBook(bookId = "b1", userId = "u1")

            assertEquals(listOf("s3", "s2", "s1"), result.map { it.id })
        }

    @Test
    fun `observeForBook emits initial list and re-emits on upsert`() =
        runTest {
            seedBook()
            userSessionDao.upsertAll(listOf(session(id = "s1", startedAt = 1_000L)))

            userSessionDao.observeForBook(bookId = "b1", userId = "u1").test {
                assertEquals(listOf("s1"), awaitItem().map { it.id })

                userSessionDao.upsertAll(listOf(session(id = "s2", startedAt = 2_000L)))
                assertEquals(listOf("s2", "s1"), awaitItem().map { it.id })

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `deleteForBook removes only that book+user's sessions`() =
        runTest {
            seedBook(id = "b1")
            seedBook(id = "b2")
            userSessionDao.upsertAll(
                listOf(
                    session(id = "s1", bookId = "b1", userId = "u1"),
                    session(id = "s2", bookId = "b2", userId = "u1"),
                    session(id = "s3", bookId = "b1", userId = "u2"),
                ),
            )

            userSessionDao.deleteForBook(bookId = "b1", userId = "u1")

            assertTrue(userSessionDao.getForBook("b1", "u1").isEmpty())
            assertEquals(listOf("s2"), userSessionDao.getForBook("b2", "u1").map { it.id })
            assertEquals(listOf("s3"), userSessionDao.getForBook("b1", "u2").map { it.id })
        }

    @Test
    fun `cascade delete removes sessions when book is deleted`() =
        runTest {
            seedBook(id = "b1")
            userSessionDao.upsertAll(
                listOf(
                    session(id = "s1", bookId = "b1"),
                    session(id = "s2", bookId = "b1"),
                ),
            )

            bookDao.deleteById(BookId("b1"))

            assertTrue(userSessionDao.getForBook("b1", "u1").isEmpty())
        }
}
