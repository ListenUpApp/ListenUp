package com.calypsan.listenup.client.download

import android.app.job.JobParameters
import androidx.work.WorkInfo
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Every stop code either transport can report maps to a reason a shared log and a doctor can read, and the raw code
 * stays beside it, so a reason a newer release adds is never lost.
 */
class StopReasonMappingTest :
    FunSpec({
        listOf(
            WorkInfo.STOP_REASON_NOT_STOPPED to DownloadStopReason.UNKNOWN,
            WorkInfo.STOP_REASON_UNKNOWN to DownloadStopReason.UNKNOWN,
            WorkInfo.STOP_REASON_CANCELLED_BY_APP to DownloadStopReason.CANCELLED_BY_APP,
            WorkInfo.STOP_REASON_PREEMPT to DownloadStopReason.PREEMPT,
            WorkInfo.STOP_REASON_TIMEOUT to DownloadStopReason.SYSTEM_TIMEOUT,
            JobParameters.STOP_REASON_TIMEOUT_ABANDONED to DownloadStopReason.SYSTEM_TIMEOUT,
            WorkInfo.STOP_REASON_DEVICE_STATE to DownloadStopReason.DEVICE_STATE,
            WorkInfo.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW to DownloadStopReason.OTHER_CONSTRAINT,
            WorkInfo.STOP_REASON_CONSTRAINT_CHARGING to DownloadStopReason.OTHER_CONSTRAINT,
            WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY to DownloadStopReason.NETWORK_CONSTRAINT,
            WorkInfo.STOP_REASON_CONSTRAINT_DEVICE_IDLE to DownloadStopReason.OTHER_CONSTRAINT,
            WorkInfo.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW to DownloadStopReason.STORAGE_CONSTRAINT,
            WorkInfo.STOP_REASON_QUOTA to DownloadStopReason.QUOTA,
            WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION to DownloadStopReason.BACKGROUND_RESTRICTION,
            WorkInfo.STOP_REASON_APP_STANDBY to DownloadStopReason.APP_STANDBY,
            WorkInfo.STOP_REASON_USER to DownloadStopReason.USER_STOPPED_FROM_SYSTEM,
            WorkInfo.STOP_REASON_SYSTEM_PROCESSING to DownloadStopReason.SYSTEM_PROCESSING,
            WorkInfo.STOP_REASON_ESTIMATED_APP_LAUNCH_TIME_CHANGED to DownloadStopReason.SYSTEM_PROCESSING,
            WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT to DownloadStopReason.FGS_TIMEOUT,
            4_242 to DownloadStopReason.UNKNOWN,
        ).forEach { (code, reason) ->
            test("stop code $code reads as $reason and keeps its code") {
                stopCauseOf(code) shouldBe StopCause(reason, code)
            }
        }

        test("a user-initiated job reschedules after any stop but the app's own and the person's") {
            wantsRescheduleAfterStop(JobParameters.STOP_REASON_QUOTA) shouldBe true
            wantsRescheduleAfterStop(JobParameters.STOP_REASON_TIMEOUT) shouldBe true
            wantsRescheduleAfterStop(JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY) shouldBe true
            wantsRescheduleAfterStop(JobParameters.STOP_REASON_CANCELLED_BY_APP) shouldBe false
            wantsRescheduleAfterStop(JobParameters.STOP_REASON_USER) shouldBe false
        }

        test("only the app's and the person's own stops wait for the person") {
            DownloadStopReason.entries.filterNot { it.resumesByItself }.toSet() shouldBe
                setOf(
                    DownloadStopReason.USER_PAUSED,
                    DownloadStopReason.USER_CANCELLED,
                    DownloadStopReason.USER_STOPPED_FROM_SYSTEM,
                    DownloadStopReason.CANCELLED_BY_APP,
                    DownloadStopReason.AUTH_EXPIRED,
                )
        }
    })
