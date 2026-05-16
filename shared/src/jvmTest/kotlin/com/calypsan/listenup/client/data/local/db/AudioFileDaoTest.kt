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
 * Verifies [AudioFileDao] against a real in-memory [ListenUpDatabase].
 *
 * Covers the DAO contract PlaybackManager + AppleDownloadService rely on:
 * ordering by index ASC, Flow re-emission on upsert, scoped deletes, and
 * FK CASCADE on book deletion.
 */
class AudioFileDaoTest {
    private val db: ListenUpDatabase = createInMemoryTestDatabase()
    private val bookDao = db.bookDao()
    private val audioFileDao = db.audioFileDao()

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

    private fun audioFile(
        bookId: String = "b1",
        index: Int,
        id: String = "af-$bookId-$index",
        filename: String = "chapter${index + 1}.m4b",
        duration: Long = 1_800_000L,
    ): AudioFileEntity =
        AudioFileEntity(
            bookId = BookId(bookId),
            index = index,
            id = id,
            filename = filename,
            format = "m4b",
            codec = "aac",
            duration = duration,
            size = 45_000_000L,
        )

    @Test
    fun `upsertAll and getForBook returns rows ordered by index ASC`() =
        runTest {
            seedBook()
            audioFileDao.upsertAll(
                listOf(
                    audioFile(index = 2),
                    audioFile(index = 0),
                    audioFile(index = 1),
                ),
            )

            val result = audioFileDao.getForBook("b1")

            assertEquals(listOf(0, 1, 2), result.map { it.index })
        }

    @Test
    fun `observeForBook emits initial list and re-emits on upsert`() =
        runTest {
            seedBook()
            audioFileDao.upsertAll(listOf(audioFile(index = 0)))

            audioFileDao.observeForBook("b1").test {
                assertEquals(listOf(0), awaitItem().map { it.index })

                audioFileDao.upsertAll(listOf(audioFile(index = 1)))
                assertEquals(listOf(0, 1), awaitItem().map { it.index })

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `deleteForBook removes only that book's rows`() =
        runTest {
            seedBook(id = "b1")
            seedBook(id = "b2")
            audioFileDao.upsertAll(
                listOf(
                    audioFile(bookId = "b1", index = 0),
                    audioFile(bookId = "b2", index = 0),
                ),
            )

            audioFileDao.deleteForBook("b1")

            assertTrue(audioFileDao.getForBook("b1").isEmpty())
            assertEquals(listOf(0), audioFileDao.getForBook("b2").map { it.index })
        }

    @Test
    fun `cascade delete removes rows when book is deleted`() =
        runTest {
            seedBook(id = "b1")
            audioFileDao.upsertAll(
                listOf(
                    audioFile(bookId = "b1", index = 0),
                    audioFile(bookId = "b1", index = 1),
                ),
            )

            bookDao.deleteById(BookId("b1"))

            assertTrue(audioFileDao.getForBook("b1").isEmpty())
        }

    @Test
    fun `upsert with same composite PK replaces existing row`() =
        runTest {
            seedBook()
            audioFileDao.upsertAll(
                listOf(audioFile(index = 0, id = "old-id", filename = "old.m4b")),
            )
            audioFileDao.upsertAll(
                listOf(audioFile(index = 0, id = "new-id", filename = "new.m4b")),
            )

            val result = audioFileDao.getForBook("b1")
            assertEquals(1, result.size)
            assertEquals("new-id", result.first().id)
            assertEquals("new.m4b", result.first().filename)
        }
}
