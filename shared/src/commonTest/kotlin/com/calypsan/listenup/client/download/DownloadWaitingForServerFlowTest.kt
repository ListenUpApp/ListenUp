package com.calypsan.listenup.client.download

import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.api.error.DownloadError
import com.calypsan.listenup.client.data.local.db.DownloadEntity
import com.calypsan.listenup.client.data.local.db.DownloadState
import com.calypsan.listenup.client.data.repository.FakeDownloadRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Seam-level tests for the W8 Phase D flows: WAITING_FOR_SERVER re-enqueue, late-event tolerance,
 * cancel-during-WAITING_FOR_SERVER, recheck path. Per project memory `feedback_fakes_for_seams.md`:
 * hand-rolled fakes, not mocks.
 *
 * Scope: tests [FakeDownloadRepository]'s contract for the new methods. The fake mirrors
 * [com.calypsan.listenup.client.data.repository.DownloadRepositoryImpl] behavior; production tests
 * for the impl live in `DownloadRepositoryImplTest`.
 */
class DownloadWaitingForServerFlowTest {
    private fun entity(
        audioFileId: String,
        bookId: String = "book-1",
        state: DownloadState = DownloadState.QUEUED,
        totalBytes: Long = 1000L,
        downloadedBytes: Long = 0L,
        transcodeJobId: String? = null,
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
        transcodeJobId = transcodeJobId,
    )

    @Test
    fun `resumeForAudioFile re-enqueues a WAITING_FOR_SERVER row`() =
        runTest {
            val fake =
                FakeDownloadRepository(
                    initial = listOf(entity("file-1", state = DownloadState.WAITING_FOR_SERVER, transcodeJobId = "job-abc")),
                )
            val result = fake.resumeForAudioFile("file-1")
            assertIs<AppResult.Success<Unit>>(result)
            assertEquals(listOf("file-1"), fake.resumedAudioFiles)
        }

    @Test
    fun `resumeForAudioFile silently drops cancelled rows (late SSE event tolerance)`() =
        runTest {
            val fake =
                FakeDownloadRepository(
                    initial = listOf(entity("file-1", state = DownloadState.CANCELLED)),
                )
            val result = fake.resumeForAudioFile("file-1")
            assertIs<AppResult.Success<Unit>>(result)
            assertTrue(fake.resumedAudioFiles.isEmpty())
        }

    @Test
    fun `resumeForAudioFile silently drops completed rows (late SSE event tolerance)`() =
        runTest {
            val fake =
                FakeDownloadRepository(
                    initial = listOf(entity("file-1", state = DownloadState.COMPLETED)),
                )
            val result = fake.resumeForAudioFile("file-1")
            assertIs<AppResult.Success<Unit>>(result)
            assertTrue(fake.resumedAudioFiles.isEmpty())
        }

    @Test
    fun `resumeForAudioFile returns Failure for missing rows`() =
        runTest {
            val fake = FakeDownloadRepository(initial = emptyList())
            val result = fake.resumeForAudioFile("missing-file")
            assertIs<AppResult.Failure>(result)
            assertIs<DownloadError.DownloadFailed>(result.error)
        }

    @Test
    fun `cancelForBook transitions all non-terminal rows to CANCELLED`() =
        runTest {
            val fake =
                FakeDownloadRepository(
                    initial =
                        listOf(
                            entity("file-1", state = DownloadState.DOWNLOADING),
                            entity("file-2", state = DownloadState.WAITING_FOR_SERVER, transcodeJobId = "job-1"),
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
