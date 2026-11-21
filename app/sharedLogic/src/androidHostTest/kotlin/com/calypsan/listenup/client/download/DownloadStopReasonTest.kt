package com.calypsan.listenup.client.download

import androidx.work.WorkInfo
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The name a download stop is logged under. A stop is only diagnosable from a shared log if the
 * line says which of WorkManager's reasons it was, so every reason WorkManager defines has a name,
 * and a reason a newer WorkManager adds still prints its number rather than vanishing.
 */
class DownloadStopReasonTest :
    FunSpec({
        listOf(
            WorkInfo.STOP_REASON_NOT_STOPPED to "NOT_STOPPED",
            WorkInfo.STOP_REASON_UNKNOWN to "UNKNOWN",
            WorkInfo.STOP_REASON_CANCELLED_BY_APP to "CANCELLED_BY_APP",
            WorkInfo.STOP_REASON_PREEMPT to "PREEMPT",
            WorkInfo.STOP_REASON_TIMEOUT to "TIMEOUT",
            WorkInfo.STOP_REASON_DEVICE_STATE to "DEVICE_STATE",
            WorkInfo.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW to "CONSTRAINT_BATTERY_NOT_LOW",
            WorkInfo.STOP_REASON_CONSTRAINT_CHARGING to "CONSTRAINT_CHARGING",
            WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY to "CONSTRAINT_CONNECTIVITY",
            WorkInfo.STOP_REASON_CONSTRAINT_DEVICE_IDLE to "CONSTRAINT_DEVICE_IDLE",
            WorkInfo.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW to "CONSTRAINT_STORAGE_NOT_LOW",
            WorkInfo.STOP_REASON_QUOTA to "QUOTA",
            WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION to "BACKGROUND_RESTRICTION",
            WorkInfo.STOP_REASON_APP_STANDBY to "APP_STANDBY",
            WorkInfo.STOP_REASON_USER to "USER",
            WorkInfo.STOP_REASON_SYSTEM_PROCESSING to "SYSTEM_PROCESSING",
            WorkInfo.STOP_REASON_ESTIMATED_APP_LAUNCH_TIME_CHANGED to "ESTIMATED_APP_LAUNCH_TIME_CHANGED",
            WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT to "FOREGROUND_SERVICE_TIMEOUT",
        ).forEach { (code, name) ->
            test("stop reason $code is logged as $name") {
                downloadStopReasonName(code) shouldBe name
            }
        }

        test("a stop reason this build does not know still logs its number") {
            downloadStopReasonName(4_242) shouldBe "UNRECOGNISED(4242)"
        }
    })
