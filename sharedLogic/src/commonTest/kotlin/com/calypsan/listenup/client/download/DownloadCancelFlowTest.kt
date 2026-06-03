package com.calypsan.listenup.client.download

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.data.local.db.DownloadEntity
import com.calypsan.listenup.client.data.local.db.DownloadState
import com.calypsan.listenup.client.data.repository.FakeDownloadRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Seam-level tests for [FakeDownloadRepository.cancelForBook]. Per project memory
 * `feedback_fakes_for_seams.md`: hand-rolled fakes, not mocks.
 *
 * Scope: the fake mirrors
 * [com.calypsan.listenup.client.data.repository.DownloadRepositoryImpl] behavior; production tests
 * for the impl live in `DownloadRepositoryImplTest`.
 */
class DownloadCancelFlowTest {
    private fun entity(
        audioFileId: String,
        bookId: String = "book-1",
        state: DownloadState = DownloadState.QUEUED,
        totalBytes: Long = 1000L,
        downloadedBytes: Long = 0L,
    ) = DownloadEntity(
        audioFileId = audioFileId,
        bookId = bookId,
        filename = "$audioFileId.mp3",
        fileIndex = 0,
        state = state,
        localPath = null,
        totalBytes = totalBytes,
        downloadedBytes = downloadedBytes,
        queuedAt = 0L,
        startedAt = null,
        completedAt = null,
        errorMessage = null,
        retryCount = 0,
    )

    @Test
    fun `cancelForBook transitions all non-terminal rows to CANCELLED`() =
        runTest {
            val fake =
                FakeDownloadRepository(
                    initial =
                        listOf(
                            entity("file-1", state = DownloadState.DOWNLOADING),
                            entity("file-2", state = DownloadState.PAUSED),
                            entity("file-3", state = DownloadState.QUEUED),
                        ),
                )
            fake.cancelForBook(BookId("book-1"))
            val final = fake.entities.associateBy { it.audioFileId }
            assertEquals(DownloadState.CANCELLED, final["file-1"]!!.state)
            assertEquals(DownloadState.CANCELLED, final["file-2"]!!.state)
            assertEquals(DownloadState.CANCELLED, final["file-3"]!!.state)
        }

    @Test
    fun `cancelForBook preserves COMPLETED rows`() =
        runTest {
            val fake =
                FakeDownloadRepository(
                    initial =
                        listOf(
                            entity("file-1", state = DownloadState.DOWNLOADING),
                            entity("file-2", state = DownloadState.COMPLETED),
                        ),
                )
            fake.cancelForBook(BookId("book-1"))
            val final = fake.entities.associateBy { it.audioFileId }
            assertEquals(DownloadState.CANCELLED, final["file-1"]!!.state)
            assertEquals(DownloadState.COMPLETED, final["file-2"]!!.state)
        }

    @Test
    fun `cancelForBook only affects the target book`() =
        runTest {
            val fake =
                FakeDownloadRepository(
                    initial =
                        listOf(
                            entity("file-1", bookId = "book-1", state = DownloadState.DOWNLOADING),
                            entity("file-2", bookId = "book-2", state = DownloadState.DOWNLOADING),
                        ),
                )
            fake.cancelForBook(BookId("book-1"))
            val final = fake.entities.associateBy { it.audioFileId }
            assertEquals(DownloadState.CANCELLED, final["file-1"]!!.state)
            assertEquals(DownloadState.DOWNLOADING, final["file-2"]!!.state)
        }
}
