package com.calypsan.listenup.client.data.local.db

import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Regression tests for [DownloadDao] queries added in W8 Phase D.
 *
 * Specifically guards C1: the three new queries must compare against the TEXT value
 * 'WAITING_FOR_SERVER' (as stored by [Converters.fromDownloadState]), not against an
 * integer ordinal. Running against a real in-memory [ListenUpDatabase] ensures Room's
 * generated SQL and the type-converter round-trip are both exercised.
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
        transcodeJobId: String? = null,
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
        transcodeJobId = transcodeJobId,
    )

    @Test
    fun `getWaitingForServer returns only WAITING_FOR_SERVER rows`() =
        runTest {
            dao.insertAll(
                listOf(
                    entity("file-1", state = DownloadState.WAITING_FOR_SERVER, transcodeJobId = "job-1"),
                    entity("file-2", state = DownloadState.DOWNLOADING),
                    entity("file-3", state = DownloadState.WAITING_FOR_SERVER, transcodeJobId = "job-3"),
                ),
            )

            val waiting = dao.getWaitingForServer()

            assertEquals(2, waiting.size)
            assertEquals(setOf("file-1", "file-3"), waiting.map { it.audioFileId }.toSet())
        }

    @Test
    fun `markWaitingForServer transitions state and persists transcodeJobId`() =
        runTest {
            dao.insert(entity("file-1", state = DownloadState.DOWNLOADING))
            dao.markWaitingForServer("file-1", "job-abc")

            val updated = dao.getByAudioFileId("file-1")
            assertNotNull(updated)
            assertEquals(DownloadState.WAITING_FOR_SERVER, updated.state)
            assertEquals("job-abc", updated.transcodeJobId)
        }

    @Test
    fun `getOldWaitingForServer returns only rows older than threshold`() =
        runTest {
            dao.insertAll(
                listOf(
                    entity("old", state = DownloadState.WAITING_FOR_SERVER, startedAt = 100L, transcodeJobId = "job-old"),
                    entity("recent", state = DownloadState.WAITING_FOR_SERVER, startedAt = 500L, transcodeJobId = "job-recent"),
                    entity("downloading", state = DownloadState.DOWNLOADING, startedAt = 50L),
                ),
            )

            val stale = dao.getOldWaitingForServer(thresholdMs = 200L)

            assertEquals(1, stale.size)
            assertEquals("old", stale.single().audioFileId)
        }

    @Test
    fun `getIncomplete returns rows not COMPLETED and not DELETED`() =
        runTest {
            dao.insertAll(
                listOf(
                    entity("file-1", state = DownloadState.QUEUED),
                    entity("file-2", state = DownloadState.DOWNLOADING),
                    entity("file-3", state = DownloadState.COMPLETED),
                    entity("file-4", state = DownloadState.DELETED),
                    entity("file-5", state = DownloadState.WAITING_FOR_SERVER, transcodeJobId = "j"),
                ),
            )
            val incomplete = dao.getIncomplete()
            // Expect: file-1, file-2, file-5 (QUEUED, DOWNLOADING, WAITING_FOR_SERVER)
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
