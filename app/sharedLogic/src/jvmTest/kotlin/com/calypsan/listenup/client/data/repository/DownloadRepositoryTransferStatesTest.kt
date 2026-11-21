package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.error.DownloadError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.DownloadDao
import com.calypsan.listenup.client.data.local.db.DownloadEntity
import com.calypsan.listenup.client.data.local.db.DownloadState
import com.calypsan.listenup.client.domain.model.DownloadPauseReason
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.download.DownloadEnqueuer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import dev.mokkery.mock
import kotlinx.coroutines.test.runTest

/**
 * The transfer-state writes a background download makes, against a real database.
 *
 * A background transfer reports through callbacks that can arrive after the person cancelled or
 * deleted the book, so every one of these writes is guarded: a terminal row stays terminal.
 */
class DownloadRepositoryTransferStatesTest :
    FunSpec({
        suspend fun withRepository(block: suspend (DownloadDao, DownloadRepositoryImpl) -> Unit) {
            val db = createInMemoryTestDatabase()
            try {
                val dao = db.downloadDao()
                block(dao, DownloadRepositoryImpl(dao, NoBooks, NoEnqueuer))
            } finally {
                db.close()
            }
        }

        test("the first bytes move a queued file to downloading and clear why it was waiting") {
            runTest {
                withRepository { dao, repository ->
                    dao.insert(row("f1", DownloadState.QUEUED, DownloadPauseReason.APP_CLOSED))

                    repository.markTransferring("f1", startedAt = 7L) shouldBe AppResult.Success(Unit)

                    val stored = dao.getByAudioFileId("f1")
                    stored?.state shouldBe DownloadState.DOWNLOADING
                    stored?.startedAt shouldBe 7L
                    stored?.pauseReason.shouldBeNull()
                }
            }
        }

        test("bytes arriving for a cancelled file do not bring it back") {
            runTest {
                withRepository { dao, repository ->
                    dao.insert(row("f1", DownloadState.CANCELLED, null))

                    repository.markTransferring("f1", startedAt = 7L)

                    dao.getByAudioFileId("f1")?.state shouldBe DownloadState.CANCELLED
                }
            }
        }

        test("a file can go back to the queue with the reason it is waiting") {
            runTest {
                withRepository { dao, repository ->
                    dao.insert(row("f1", DownloadState.DOWNLOADING, null))

                    repository.markQueued("f1", DownloadPauseReason.URL_EXPIRED)

                    val stored = dao.getByAudioFileId("f1")
                    stored?.state shouldBe DownloadState.QUEUED
                    stored?.pauseReason shouldBe DownloadPauseReason.URL_EXPIRED
                    stored?.retryCount shouldBe 0
                }
            }
        }

        test("a finished or cancelled file never goes back to the queue") {
            runTest {
                withRepository { dao, repository ->
                    dao.insert(row("done", DownloadState.COMPLETED, null))
                    dao.insert(row("gone", DownloadState.CANCELLED, null))

                    repository.markQueued("done", null)
                    repository.markQueued("gone", DownloadPauseReason.APP_CLOSED)

                    dao.getByAudioFileId("done")?.state shouldBe DownloadState.COMPLETED
                    dao.getByAudioFileId("gone")?.state shouldBe DownloadState.CANCELLED
                }
            }
        }

        test("a failure reported after the person cancelled leaves the row cancelled") {
            runTest {
                withRepository { dao, repository ->
                    dao.insert(row("f1", DownloadState.CANCELLED, null))

                    repository.markFailed("f1", DownloadError.DownloadFailed())

                    val stored = dao.getByAudioFileId("f1")
                    stored?.state shouldBe DownloadState.CANCELLED
                    stored?.retryCount shouldBe 0
                }
            }
        }

        test("a download you paused is not restarted by the sweep that resumes the system's pauses") {
            runTest {
                withRepository { dao, _ ->
                    dao.insert(row("yours", DownloadState.PAUSED, DownloadPauseReason.PAUSED_BY_YOU))
                    dao.insert(row("systems", DownloadState.PAUSED, DownloadPauseReason.INTERRUPTED))

                    dao.getIncomplete().map { it.audioFileId } shouldBe listOf("systems")
                }
            }
        }
    })

private fun row(
    id: String,
    state: DownloadState,
    reason: DownloadPauseReason?,
): DownloadEntity =
    DownloadEntity(
        audioFileId = id,
        bookId = "b1",
        filename = "$id.m4b",
        fileIndex = 0,
        state = state,
        localPath = null,
        totalBytes = 100L,
        downloadedBytes = 10L,
        queuedAt = 0L,
        startedAt = null,
        completedAt = null,
        errorMessage = null,
        retryCount = 0,
        pauseReason = reason,
    )

private val NoBooks: BookRepository = mock()

private object NoEnqueuer : DownloadEnqueuer {
    override suspend fun enqueue(entity: DownloadEntity): AppResult<Unit> = AppResult.Success(Unit)
}
