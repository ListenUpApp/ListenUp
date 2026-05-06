package com.calypsan.listenup.client.download

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.calypsan.listenup.client.core.error.ErrorBus
import com.calypsan.listenup.client.data.remote.ABSImportApiContract
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import com.calypsan.listenup.client.data.remote.BackupApiContract
import com.calypsan.listenup.client.data.remote.PlaybackApiContract
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.playback.AudioCapabilityDetector
import com.calypsan.listenup.client.upload.ABSUploadWorker
import kotlinx.coroutines.runBlocking

/**
 * WorkerFactory for injecting dependencies into all ListenUp workers.
 *
 * Handles creation of:
 * - [DownloadWorker] — offline audiobook downloads
 * - [ABSUploadWorker] — Audiobookshelf backup upload and import
 */
class ListenUpWorkerFactory(
    private val downloadRepository: DownloadRepository,
    private val fileManager: DownloadFileManager,
    // Deferred to worker-creation time so getClient() is never called before onboarding
    // completes (i.e. before serverConfig.getActiveUrl() returns non-null).
    private val apiClientFactory: ApiClientFactory,
    private val playbackPreferences: PlaybackPreferences,
    private val playbackApi: PlaybackApiContract,
    private val capabilityDetector: AudioCapabilityDetector,
    private val backupApi: BackupApiContract,
    private val absImportApi: ABSImportApiContract,
    private val errorBus: ErrorBus,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? =
        when (workerClassName) {
            DownloadWorker::class.java.name -> {
                // WorkerFactory.createWorker is non-suspending; runBlocking is required.
                // By the time WorkManager dispatches a download, onboarding has completed
                // and serverConfig.getActiveUrl() is non-null — no cold-start crash risk.
                val httpClient = runBlocking { apiClientFactory.getClient() }
                DownloadWorker(
                    appContext,
                    workerParameters,
                    downloadRepository,
                    fileManager,
                    httpClient,
                    playbackPreferences,
                    playbackApi,
                    capabilityDetector,
                    errorBus,
                )
            }

            ABSUploadWorker::class.java.name -> {
                ABSUploadWorker(
                    appContext,
                    workerParameters,
                    backupApi,
                    absImportApi,
                )
            }

            else -> {
                null
            }
        }
}
