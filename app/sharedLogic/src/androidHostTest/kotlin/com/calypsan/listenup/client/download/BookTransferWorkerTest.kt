package com.calypsan.listenup.client.download

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.work.ForegroundInfo
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import androidx.concurrent.futures.CallbackToFutureAdapter
import com.google.common.util.concurrent.ListenableFuture
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * [BookTransferWorker], Android 11–13's host for a book run. It becomes a `dataSync` foreground service when the
 * platform allows; when it does not (a background start on 12–13) the download carries on as a plain worker instead of
 * failing. A run's outcome becomes WorkManager's result, and a stop's reason is read only where the API exists.
 */
@RunWith(RobolectricTestRunner::class)
class BookTransferWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** A runner that records the host it was given and answers with [outcome]. */
    private class RecordingRunner(
        private val context: Context,
        private val outcome: () -> BookTransferOutcome,
    ) : BookRunner {
        var host: PlatformTransferHost? = null
        var stopCause: StopCause? = null
        var cancelledActive = false

        override val activeNotificationId = 4

        override fun startingNotification(
            bookId: String,
            title: String,
        ): Notification = NotificationCompat.Builder(context, "listenup_downloads").setContentTitle(title).build()

        override fun postActive(notification: Notification) = Unit

        override fun cancelActive() {
            cancelledActive = true
        }

        override suspend fun runOn(
            bookId: String,
            host: PlatformTransferHost,
        ): BookTransferOutcome {
            this.host = host
            stopCause = host.platformStopCause()
            host.finished()
            return outcome()
        }
    }

    private class ForegroundRecorder(
        private val refuse: Boolean,
    ) : ForegroundUpdater {
        val shown = mutableListOf<ForegroundInfo>()

        override fun setForegroundAsync(
            context: Context,
            id: UUID,
            foregroundInfo: ForegroundInfo,
        ): ListenableFuture<Void> {
            if (!refuse) shown += foregroundInfo
            return CallbackToFutureAdapter.getFuture { completer ->
                if (refuse) {
                    completer.setException(IllegalStateException("ForegroundServiceStartNotAllowed"))
                } else {
                    completer.set(null)
                }
                "setForeground"
            }
        }
    }

    private suspend fun work(
        runner: BookRunner,
        foreground: ForegroundRecorder = ForegroundRecorder(refuse = false),
    ): ListenableWorker.Result =
        TestListenableWorkerBuilder<BookTransferWorker>(context)
            .setInputData(workDataOf(BookTransferWorker.KEY_BOOK_ID to "b1", BookTransferWorker.KEY_TITLE to "Dune"))
            .setForegroundUpdater(foreground)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ) = BookTransferWorker(appContext, workerParameters, runner)
                },
            ).build()
            .doWork()

    @Test
    fun `the worker becomes a dataSync foreground service on the shared notification before it runs`() =
        runTest {
            val runner = RecordingRunner(context) { BookTransferOutcome.COMPLETED }
            val foreground = ForegroundRecorder(refuse = false)

            work(runner, foreground).shouldBeInstanceOf<ListenableWorker.Result.Success>()

            val shown = foreground.shown.first()
            shown.notificationId shouldBe 4
            shown.foregroundServiceType shouldBe android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            runner.host?.transport shouldBe DownloadTransport.WORKMANAGER_FGS
            runner.host?.foreground shouldBe true
        }

    @Test
    @Config(sdk = [33])
    fun `a refused foreground start carries on as a plain worker rather than failing the download`() =
        runTest {
            val runner = RecordingRunner(context) { BookTransferOutcome.COMPLETED }

            work(runner, ForegroundRecorder(refuse = true)).shouldBeInstanceOf<ListenableWorker.Result.Success>()

            runner.host?.transport shouldBe DownloadTransport.WORKMANAGER_PLAIN
            runner.host?.foreground shouldBe false
            // A plain worker posted its own notification, so it removes it when the run ends.
            runner.cancelledActive shouldBe true
        }

    @Test
    fun `a run that waits becomes a retry, and one that gave up a failure`() =
        runTest {
            work(RecordingRunner(context) { BookTransferOutcome.RETRY_LATER })
                .shouldBeInstanceOf<ListenableWorker.Result.Retry>()
            work(RecordingRunner(context) { BookTransferOutcome.GAVE_UP })
                .shouldBeInstanceOf<ListenableWorker.Result.Failure>()
        }

    @Test
    fun `an unexpected error waits and retries rather than failing the download`() =
        runTest {
            work(RecordingRunner(context) { error("database closed") }).shouldBeInstanceOf<ListenableWorker.Result.Retry>()
        }

    @Test
    @Config(sdk = [30])
    fun `on Android 11 a stop is recorded as unavailable, never read from an API that is not there`() =
        runTest {
            val runner = RecordingRunner(context) { BookTransferOutcome.COMPLETED }

            work(runner)

            runner.stopCause shouldBe STOP_CAUSE_UNAVAILABLE_ON_API_30
        }

    @Test
    @Config(sdk = [31])
    fun `from Android 12 a stop carries WorkManager's own reason code`() =
        runTest {
            val runner = RecordingRunner(context) { BookTransferOutcome.COMPLETED }

            work(runner)

            // Not stopped in this run: WorkManager's "not stopped" code, kept beside its reading.
            runner.stopCause shouldBe StopCause(DownloadStopReason.UNKNOWN, androidx.work.WorkInfo.STOP_REASON_NOT_STOPPED)
        }
}
