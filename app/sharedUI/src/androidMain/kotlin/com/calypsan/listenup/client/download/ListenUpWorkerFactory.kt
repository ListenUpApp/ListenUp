package com.calypsan.listenup.client.download

import android.content.Context
import android.os.Build
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.calypsan.listenup.client.diagnostics.JobReasonLogger
import com.calypsan.listenup.client.domain.repository.AdminRepository
import com.calypsan.listenup.client.push.RegistrationDecisionWorker

/**
 * WorkerFactory for injecting dependencies into all ListenUp workers.
 *
 * All dependencies are [Lazy] so that constructing the factory does not resolve the
 * download/WorkManager graph. Resolution is deferred until [createWorker] is called at
 * job-dispatch time — long after [WorkManager.initialize] has run during app startup.
 *
 * Handles creation of:
 * - [BookTransferWorker] — a book's download on Android 11–13
 * - [DownloadWorker] — the retired per-file worker, which drains already-persisted work for one release
 * - [RegistrationDecisionWorker] — Approve/Deny taken from a registration notification
 */
class ListenUpWorkerFactory(
    // Book runs, deferred to worker-creation time so the download graph (and the authenticated client behind it) is
    // never resolved before onboarding completes.
    private val bookTransfers: Lazy<BookTransfers>,
    // Approve/Deny straight from a registration notification (#1068). Lazy like its siblings so
    // constructing the factory never resolves the admin graph.
    private val adminRepository: Lazy<AdminRepository>,
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
            RegistrationDecisionWorker::class.java.name -> {
                RegistrationDecisionWorker(appContext, workerParameters, adminRepository.value)
            }

            BookTransferWorker::class.java.name -> {
                BookTransferWorker(appContext, workerParameters, bookTransfers.value)
            }

            // The per-file worker of earlier releases: drains without a dependency, for one release.
            DownloadWorker::class.java.name -> {
                DownloadWorker(appContext, workerParameters)
            }

            else -> {
                null
            }
        }
    }
}
