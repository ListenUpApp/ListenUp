package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.Success
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.data.local.db.CoverDownloadDao
import com.calypsan.listenup.client.data.local.db.CoverDownloadStatus
import com.calypsan.listenup.client.data.local.db.CoverDownloadTaskEntity
import dev.mokkery.answering.returns
import dev.mokkery.answering.sequentiallyReturns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class CoverDownloadWorkerTest :
    FunSpec({

        fun createTask(id: String) =
            CoverDownloadTaskEntity(
                bookId = BookId(id),
                status = CoverDownloadStatus.PENDING,
                attempts = 0,
                createdAt = Timestamp.now(),
            )

        test("processQueue delays between each download") {
            runTest {
                val dao: CoverDownloadDao = mock()
                val downloader: ImageDownloaderContract = mock()

                val tasks = listOf(createTask("a"), createTask("b"), createTask("c"))

                // First call returns tasks, second returns empty to stop loop
                everySuspend { dao.getNextBatch(limit = any(), maxRetries = any()) } sequentiallyReturns listOf(tasks, emptyList())
                everySuspend { dao.markInProgress(any()) } returns Unit
                everySuspend { dao.markCompleted(any(), any()) } returns Unit
                every { dao.observeRemainingCount() } returns flowOf(0)
                every { dao.observeCompletedCount() } returns flowOf(0)
                every { dao.observeTotalCount() } returns flowOf(0)
                everySuspend { downloader.downloadCover(any()) } returns Success(true)

                val worker = CoverDownloadWorker(dao, downloader)

                val startTime = currentTime
                worker.processQueue()
                val elapsed = currentTime - startTime

                // 3 tasks * 500ms delay = 1500ms minimum
                val expectedMinimum = 3 * 500L
                (elapsed >= expectedMinimum) shouldBe true

                // Verify each task was marked in-progress then completed
                for (task in tasks) {
                    verifySuspend { dao.markInProgress(task.bookId) }
                    verifySuspend { dao.markCompleted(task.bookId, any()) }
                }
            }
        }
    })
