package com.calypsan.listenup.client.download

import android.content.Context
import android.os.Build
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.calypsan.listenup.core.error.ErrorBus
import com.calypsan.listenup.client.data.remote.ABSImportApiContract
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.diagnostics.JobReasonLogger
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import com.calypsan.listenup.client.data.remote.BackupApiContract
import com.calypsan.listenup.client.data.remote.PlaybackRpcFactory
import com.calypsan.listenup.client.upload.ABSUploadWorker
import kotlinx.coroutines.runBlocking

/**
 * WorkerFactory for injecting dependencies into all ListenUp workers.
 *
 * All dependencies are [Lazy] so that constructing the factory does not resolve the
 * download/WorkManager graph. Resolution is deferred until [createWorker] is called at
 * job-dispatch time — long after [WorkManager.initialize] has run during app startup.
 *
 * Handles creation of:
 * - [DownloadWorker] — offline audiobook downloads
 * - [ABSUploadWorker] — Audiobookshelf backup upload and import
 */
class ListenUpWorkerFactory(
    private val downloadRepository: Lazy<DownloadRepository>,
    private val fileManager: Lazy<DownloadFileManager>,
    // Deferred to worker-creation time so getClient() is never called before onboarding
    // completes (i.e. before serverConfig.getActiveUrl() returns non-null).
    private val apiClientFactory: Lazy<ApiClientFactory>,
    private val playbackRpcFactory: Lazy<PlaybackRpcFactory>,
    private val backupApi: Lazy<BackupApiContract>,
    private val absImportApi: Lazy<ABSImportApiContract>,
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
        return when (workerClassName) {
            DownloadWorker::class.java.name -> {
                // runBlocking hits the cache primed at auth completion
                // (ListenUp.onCreate's apiClientFactory.value.getClient() call). The only
                // blocking case is the cold-race window between auth completion and
                // pre-warm completion.
                val httpClient = runBlocking { apiClientFactory.value.getClient() }
                DownloadWorker(
                    appContext,
                    workerParameters,
                    downloadRepository.value,
                    fileManager.value,
                    httpClient,
                    playbackRpcFactory.value,
                    errorBus.value,
                )
            }

            ABSUploadWorker::class.java.name -> {
                ABSUploadWorker(
                    appContext,
                    workerParameters,
                    backupApi.value,
                    absImportApi.value,
                )
            }

            else -> {
                null
            }
        }
    }
}
