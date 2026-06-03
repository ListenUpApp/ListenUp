package com.calypsan.listenup.client.data.local.db

import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Regression tests for [DownloadDao] queries. Running against a real in-memory
 * [ListenUpDatabase] ensures Room's generated SQL and the type-converter round-trip
 * are both exercised.
 */
class DownloadDaoTest {
    private val db: ListenUpDatabase = createInMemoryTestDatabase()
    private val dao: DownloadDao = db.downloadDao()

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun entity(
        audioFileId: String,
        bookId: String = "book-1",
        state: DownloadState = DownloadState.QUEUED,
        startedAt: Long? = null,
    ) = DownloadEntity(
        audioFileId = audioFileId,
        bookId = bookId,
        filename = "$audioFileId.mp3",
        fileIndex = 0,
        state = state,
        localPath = null,
        totalBytes = 1000L,
        downloadedBytes = 0L,
        queuedAt = 0L,
        startedAt = startedAt,
        completedAt = null,
        errorMessage = null,
        retryCount = 0,
    )

    @Test
    fun `getIncomplete returns rows not COMPLETED and not DELETED`() =
        runTest {
            dao.insertAll(
                listOf(
                    entity("file-1", state = DownloadState.QUEUED),
                    entity("file-2", state = DownloadState.DOWNLOADING),
                    entity("file-3", state = DownloadState.COMPLETED),
                    entity("file-4", state = DownloadState.DELETED),
                    entity("file-5", state = DownloadState.PAUSED),
                ),
            )
            val incomplete = dao.getIncomplete()
            // Expect: file-1, file-2, file-5 (QUEUED, DOWNLOADING, PAUSED)
            // Reject: file-3 (COMPLETED), file-4 (DELETED)
            assertEquals(3, incomplete.size)
            assertEquals(setOf("file-1", "file-2", "file-5"), incomplete.map { it.audioFileId }.toSet())
        }

    @Test
    fun `getLocalPath returns localPath for COMPLETED rows only`() =
        runTest {
            dao.insertAll(
                listOf(
                    entity("file-1", state = DownloadState.COMPLETED).copy(localPath = "/path/to/file-1"),
                    entity("file-2", state = DownloadState.DOWNLOADING).copy(localPath = "/path/to/file-2"),
                ),
            )
            assertEquals("/path/to/file-1", dao.getLocalPath("file-1"))
            // For non-COMPLETED rows, query should return null even if localPath is set.
            assertNull(dao.getLocalPath("file-2"))
        }

    @Test
    fun `markDeletedForBook transitions all rows for a book to DELETED`() =
        runTest {
            dao.insertAll(
                listOf(
                    entity("file-1", bookId = "book-1", state = DownloadState.COMPLETED).copy(localPath = "/path"),
                    entity("file-2", bookId = "book-1", state = DownloadState.DOWNLOADING),
                    entity("file-3", bookId = "book-2", state = DownloadState.QUEUED), // different book
                ),
            )
            dao.markDeletedForBook("book-1")
            val all = dao.observeAll().first()
            val byId = all.associateBy { it.audioFileId }
            assertEquals(DownloadState.DELETED, byId["file-1"]!!.state)
            assertEquals(DownloadState.DELETED, byId["file-2"]!!.state)
            assertNull(byId["file-1"]!!.localPath) // should be cleared
            assertEquals(DownloadState.QUEUED, byId["file-3"]!!.state) // book-2 unaffected
        }

    @Test
    fun `hasDeletedRecords returns true if any DELETED row exists for a book`() =
        runTest {
            dao.insertAll(
                listOf(
                    entity("file-1", bookId = "book-1", state = DownloadState.DELETED),
                    entity("file-2", bookId = "book-2", state = DownloadState.COMPLETED),
                ),
            )
            assertEquals(true, dao.hasDeletedRecords("book-1"))
            assertEquals(false, dao.hasDeletedRecords("book-2"))
            assertEquals(false, dao.hasDeletedRecords("book-3")) // non-existent book
        }
}
