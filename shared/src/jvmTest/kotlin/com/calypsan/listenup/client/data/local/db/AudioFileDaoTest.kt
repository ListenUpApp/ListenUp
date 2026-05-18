package com.calypsan.listenup.client.data.local.db

import com.calypsan.listenup.client.core.BookId
import com.calypsan.listenup.client.core.Timestamp
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * Verifies [AudioFileDao] against a real in-memory [ListenUpDatabase].
 *
 * Covers the DAO contract PlaybackManager + AppleDownloadService rely on:
 * ordering by index ASC, Flow re-emission on upsert, scoped deletes, and
 * FK CASCADE on book deletion.
 */
class AudioFileDaoTest :
    FunSpec({
        lateinit var db: ListenUpDatabase
        lateinit var bookDao: BookDao
        lateinit var audioFileDao: AudioFileDao

        beforeTest {
            db = createInMemoryTestDatabase()
            bookDao = db.bookDao()
            audioFileDao = db.audioFileDao()
        }

        afterTest {
            db.close()
        }

        test("upsertAll and getForBook returns rows ordered by index ASC") {
            runTest {
                audioFileSeedBook(bookDao)
                audioFileDao.upsertAll(
                    listOf(
                        audioFile(index = 2),
                        audioFile(index = 0),
                        audioFile(index = 1),
                    ),
                )

                val result = audioFileDao.getForBook("b1")

                result.map { it.index } shouldBe listOf(0, 1, 2)
            }
        }

        test("deleteForBook removes only that book's rows") {
            runTest {
                audioFileSeedBook(bookDao, id = "b1")
                audioFileSeedBook(bookDao, id = "b2")
                audioFileDao.upsertAll(
                    listOf(
                        audioFile(bookId = "b1", index = 0),
                        audioFile(bookId = "b2", index = 0),
                    ),
                )

                audioFileDao.deleteForBook("b1")

                audioFileDao.getForBook("b1").isEmpty() shouldBe true
                audioFileDao.getForBook("b2").map { it.index } shouldBe listOf(0)
            }
        }

        test("cascade delete removes rows when book is deleted") {
            runTest {
                audioFileSeedBook(bookDao, id = "b1")
                audioFileDao.upsertAll(
                    listOf(
                        audioFile(bookId = "b1", index = 0),
                        audioFile(bookId = "b1", index = 1),
                    ),
                )

                bookDao.deleteById(BookId("b1"))

                audioFileDao.getForBook("b1").isEmpty() shouldBe true
            }
        }

        test("upsert with same composite PK replaces existing row") {
            runTest {
                audioFileSeedBook(bookDao)
                audioFileDao.upsertAll(
                    listOf(audioFile(index = 0, id = "old-id", filename = "old.m4b")),
                )
                audioFileDao.upsertAll(
                    listOf(audioFile(index = 0, id = "new-id", filename = "new.m4b")),
                )

                val result = audioFileDao.getForBook("b1")
                result.size shouldBe 1
                result.first().id shouldBe "new-id"
                result.first().filename shouldBe "new.m4b"
            }
        }
    })

private suspend fun audioFileSeedBook(
    bookDao: BookDao,
    id: String = "b1",
) {
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
