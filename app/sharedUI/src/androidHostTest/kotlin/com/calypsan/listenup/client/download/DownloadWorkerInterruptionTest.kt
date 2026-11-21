package com.calypsan.listenup.client.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.DownloadPauseReason
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [DownloadWorker] under the interruptions a phone in a pocket actually produces.
 *
 * WorkManager counts a run the system stopped as an attempt, so `runAttemptCount` says nothing
 * about how often a download really failed. These tests drive the worker through
 * [TestListenableWorkerBuilder] with a high attempt count to prove the worker no longer reads it,
 * and with a connection lost mid-read to prove that is a wait rather than a failure.
 *
 * JUnit4 + Robolectric like the module's other worker tests ([TestListenableWorkerBuilder] needs a
 * real [Context]). [DownloadRepository] is a mock for the reason `RegistrationDecisionWorkerTest`
 * gives: ~25 members, of which the worker touches four.
 */
@RunWith(RobolectricTestRunner::class)
class DownloadWorkerInterruptionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun repository(failuresAfterThisOne: Int = 1) =
        mock<DownloadRepository>(MockMode.autoUnit) {
            everySuspend { markDownloading(any(), any()) } returns AppResult.Success(Unit)
            everySuspend { markPaused(any(), any()) } returns AppResult.Success(Unit)
            everySuspend { markFailed(any(), any()) } returns AppResult.Success(failuresAfterThisOne)
        }

    private fun downloaderFailingWith(error: AppError) =
        object : AudioFileDownloader {
            override suspend fun download(
                audioFileId: String,
                bookId: String,
                filename: String,
                expectedSize: Long,
                isStopped: () -> Boolean,
                setProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit,
            ): AppResult<Unit> = AppResult.Failure(error)
        }

    private suspend fun runWorker(
        repository: DownloadRepository,
        error: AppError,
        errorBus: ErrorBus,
        runAttemptCount: Int,
    ): ListenableWorker.Result =
        TestListenableWorkerBuilder<DownloadWorker>(context)
            .setInputData(
                workDataOf(
                    DownloadWorker.KEY_AUDIO_FILE_ID to "af1",
                    DownloadWorker.KEY_BOOK_ID to "b1",
                    DownloadWorker.KEY_FILENAME to "af1.m4b",
                    DownloadWorker.KEY_FILE_SIZE to 1_000L,
                ),
            ).setRunAttemptCount(runAttemptCount)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ) = DownloadWorker(
                        context = appContext,
                        params = workerParameters,
                        downloadRepository = repository,
                        fileManager = DownloadFileManager(appContext),
                        audioFileDownloader = downloaderFailingWith(error),
                        errorBus = errorBus,
                    )
                },
            ).build()
            .doWork()

    /** Every error [errorBus] carries while [block] runs. */
    private suspend fun errorsDuring(
        errorBus: ErrorBus,
        block: suspend () -> Unit,
    ): List<AppError> {
        val seen = mutableListOf<AppError>()
        val collector = CoroutineScope(Dispatchers.Unconfined)
        collector.launch { errorBus.errors.collect { seen += it } }
        try {
            block()
        } finally {
            collector.cancel()
        }
        return seen
    }

    @Test
    fun `a connection lost mid-read pauses and waits for the network, charging no retry and raising no error`() =
        runTest {
            val repository = repository()
            val errorBus = ErrorBus()
            lateinit var result: ListenableWorker.Result

            val errors =
                errorsDuring(errorBus) {
                    result =
                        runWorker(
                            repository = repository,
                            error = TransportError.NetworkUnavailable(debugInfo = "Software caused connection abort"),
                            errorBus = errorBus,
                            runAttemptCount = 0,
                        )
                }

            result.shouldBeInstanceOf<ListenableWorker.Result.Retry>()
            errors.shouldBeEmpty()
            verifySuspend { repository.markPaused("af1", DownloadPauseReason.NETWORK_LOST) }
            verifySuspend(VerifyMode.not) { repository.markFailed(any(), any()) }
        }

    @Test
    fun `three system stops then a real failure still leave retry budget`() =
        runTest {
            // WorkManager has counted three stopped runs; the row has spent one real failure.
            val repository = repository(failuresAfterThisOne = 1)

            val result =
                runWorker(
                    repository = repository,
                    error = TransportError.Server5xx(statusCode = 503),
                    errorBus = ErrorBus(),
                    runAttemptCount = 3,
                )

            result.shouldBeInstanceOf<ListenableWorker.Result.Retry>()
        }

    @Test
    fun `a real failure that spends the row's last retry gives up`() =
        runTest {
            val repository = repository(failuresAfterThisOne = 3)

            val result =
                runWorker(
                    repository = repository,
                    error = TransportError.Server5xx(statusCode = 503),
                    errorBus = ErrorBus(),
                    runAttemptCount = 0,
                )

            result.shouldBeInstanceOf<ListenableWorker.Result.Failure>()
        }
}
