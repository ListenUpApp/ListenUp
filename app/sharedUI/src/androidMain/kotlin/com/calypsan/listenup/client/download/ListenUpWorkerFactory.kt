package com.calypsan.listenup.client.download

import android.content.Context
import android.os.Build
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.calypsan.listenup.core.error.ErrorBus
import com.calypsan.listenup.client.diagnostics.JobReasonLogger
import com.calypsan.listenup.client.domain.repository.DownloadRepository

/**
 * WorkerFactory for injecting dependencies into all ListenUp workers.
 *
 * All dependencies are [Lazy] so that constructing the factory does not resolve the
 * download/WorkManager graph. Resolution is deferred until [createWorker] is called at
 * job-dispatch time — long after [WorkManager.initialize] has run during app startup.
 *
 * Handles creation of:
 * - [DownloadWorker] — offline audiobook downloads
 */
class ListenUpWorkerFactory(
    private val downloadRepository: Lazy<DownloadRepository>,
    private val fileManager: Lazy<DownloadFileManager>,
    // Deferred to worker-creation time so the underlying getClient() is never called before
    // onboarding completes (i.e. before serverConfig.getActiveUrl() returns non-null).
    private val audioFileDownloader: Lazy<AudioFileDownloader>,
    private val errorBus: Lazy<ErrorBus>,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            JobReasonLogger.logPendingReasonsFor(
                context = appContext,
                workSpecId = workerParameters.id.toString(),
                correlationId = workerParameters.id.toString(),
            )
        }
        return if (workerClassName == DownloadWorker::class.java.name) {
            // AudioFileDownloader owns the HTTP transport in :app:sharedLogic; it resolves the (cached)
            // authenticated client lazily on first download, so no client construction happens here
            // at worker-creation time.
            DownloadWorker(
                appContext,
                workerParameters,
                downloadRepository.value,
                fileManager.value,
                audioFileDownloader.value,
                errorBus.value,
            )
        } else {
            null
        }
    }
}
