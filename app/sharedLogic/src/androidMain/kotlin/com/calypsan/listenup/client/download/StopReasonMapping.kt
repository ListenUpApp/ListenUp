package com.calypsan.listenup.client.download

import android.app.job.JobParameters
import androidx.work.WorkInfo

/**
 * The [DownloadStopReason] for a platform stop code. One table serves both transports: WorkManager's `WorkInfo`
 * stop reasons mirror `JobParameters`' codes, and the two codes WorkManager adds of its own (not stopped, unknown)
 * read as unknown. A code this build does not know reads [DownloadStopReason.UNKNOWN], and the raw code is always
 * recorded beside the mapped reason, so a reason a newer release adds is never lost.
 */
internal fun downloadStopReasonOf(code: Int): DownloadStopReason =
    when (code) {
        WorkInfo.STOP_REASON_CANCELLED_BY_APP -> DownloadStopReason.CANCELLED_BY_APP

        WorkInfo.STOP_REASON_PREEMPT -> DownloadStopReason.PREEMPT

        WorkInfo.STOP_REASON_TIMEOUT, JobParameters.STOP_REASON_TIMEOUT_ABANDONED -> DownloadStopReason.SYSTEM_TIMEOUT

        WorkInfo.STOP_REASON_DEVICE_STATE -> DownloadStopReason.DEVICE_STATE

        WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY -> DownloadStopReason.NETWORK_CONSTRAINT

        WorkInfo.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW -> DownloadStopReason.STORAGE_CONSTRAINT

        WorkInfo.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW,
        WorkInfo.STOP_REASON_CONSTRAINT_CHARGING,
        WorkInfo.STOP_REASON_CONSTRAINT_DEVICE_IDLE,
        -> DownloadStopReason.OTHER_CONSTRAINT

        WorkInfo.STOP_REASON_QUOTA -> DownloadStopReason.QUOTA

        WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION -> DownloadStopReason.BACKGROUND_RESTRICTION

        WorkInfo.STOP_REASON_APP_STANDBY -> DownloadStopReason.APP_STANDBY

        WorkInfo.STOP_REASON_USER -> DownloadStopReason.USER_STOPPED_FROM_SYSTEM

        WorkInfo.STOP_REASON_SYSTEM_PROCESSING,
        WorkInfo.STOP_REASON_ESTIMATED_APP_LAUNCH_TIME_CHANGED,
        -> DownloadStopReason.SYSTEM_PROCESSING

        WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT -> DownloadStopReason.FGS_TIMEOUT

        else -> DownloadStopReason.UNKNOWN
    }

/** The stop cause for a platform [code]: its mapped reason with the raw code kept beside it. */
internal fun stopCauseOf(code: Int): StopCause = StopCause(downloadStopReasonOf(code), code)

/** Android 11 has no stop-reason API: a stop there is recorded as unavailable, never guessed at. */
internal val STOP_CAUSE_UNAVAILABLE_ON_API_30 = StopCause(DownloadStopReason.UNAVAILABLE_ON_API_30)
